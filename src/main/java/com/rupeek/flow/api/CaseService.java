package com.rupeek.flow.api;

import com.rupeek.flow.engine.FlowRunRequest;
import com.rupeek.flow.engine.FlowState;
import com.rupeek.flow.engine.FlowWorkflow;
import com.rupeek.flow.engine.ResumeAction;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.NodeResult;
import com.rupeek.flow.registry.FlowSource;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Every Temporal-facing operation of the HTTP API: start (idempotent), signal, status, resume. */
public class CaseService {

    private static final Logger LOG = LoggerFactory.getLogger(CaseService.class);

    static final Pattern FLOW_REF = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    static final Pattern BUSINESS_KEY = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    static final Pattern WORKFLOW_ID = Pattern.compile("[A-Za-z0-9._:-]{1,256}");
    static final Pattern EVENT_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    public record StartResult(boolean started, String workflowId, String runId) {
    }

    private final WorkflowClient client;
    private final FlowSource flows;
    private final String taskQueue;

    public CaseService(WorkflowClient client, FlowSource flows, String taskQueue) {
        this.client = client;
        this.flows = flows;
        this.taskQueue = taskQueue;
    }

    static String require(Pattern p, String value, String what) {
        if (value == null || !p.matcher(value).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid " + what);
        }
        return value;
    }

    /**
     * The top-level FlowDef comes from the registry (pinned {@code version}, else the latest pointer) and is
     * handed to the workflow in {@link FlowRunRequest}; SUBFLOW/TASK refs inside it resolve in-workflow as usual.
     */
    public StartResult start(String flowRef, Map<String, Object> inputs, String businessKey, Integer version) {
        require(FLOW_REF, flowRef, "flowRef");
        // Idempotency key: explicit businessKey, else inputs.loanRequestId (so the id is <flowRef>-<loanRequestId>,
        // the same id the callback derives), else random. A second start while it runs returns the existing run.
        Object loanRequestId = inputs == null ? null : inputs.get("loanRequestId");
        String key = businessKey != null && !businessKey.isBlank() ? require(BUSINESS_KEY, businessKey, "businessKey")
                : loanRequestId != null && !loanRequestId.toString().isBlank()
                        ? require(BUSINESS_KEY, loanRequestId.toString(), "inputs.loanRequestId")
                        : UUID.randomUUID().toString();
        String workflowId = flowRef + "-" + key;
        FlowDef top = flows.flow(flowRef, version != null ? version : flows.latestVersion(flowRef));

        FlowWorkflow stub = client.newWorkflowStub(FlowWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(taskQueue).setWorkflowId(workflowId)
                .setWorkflowExecutionTimeout(Duration.ofHours(1)).build());
        try {
            var exec = WorkflowClient.start(stub::run, FlowRunRequest.root(top, inputs == null ? Map.of() : inputs, Map.of()));
            LOG.info("Started flow {}@{} workflowId={}", flowRef, top.version(), workflowId);
            return new StartResult(true, workflowId, exec.getRunId());
        } catch (WorkflowExecutionAlreadyStarted dup) {
            LOG.info("Deduplicated start of flow {}: workflowId={} already running", flowRef, workflowId);
            return new StartResult(false, workflowId, dup.getExecution().getRunId());
        }
    }

    /** Delivers an external event; 404 if the workflow does not exist, 409 if it already finished. */
    public void signal(String workflowId, String eventName, Map<String, Object> payload) {
        require(WORKFLOW_ID, workflowId, "workflowId");
        require(EVENT_NAME, eventName, "eventName");
        deliver(workflowId, wf -> wf.event(eventName, payload == null ? Map.of() : payload));
        LOG.info("Signal '{}' delivered to workflowId={}", eventName, workflowId);
    }

    public void resume(String workflowId, String nodeId, String action, String outcome) {
        require(WORKFLOW_ID, workflowId, "workflowId");
        if (nodeId == null || nodeId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "nodeId is required", workflowId);
        }
        ResumeAction ra;
        if ("RETRY".equals(action)) {
            ra = ResumeAction.retry();
        } else if ("SKIP".equals(action) && outcome != null && !outcome.isBlank()) {
            ra = ResumeAction.skip(outcome);
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "action must be RETRY, or SKIP with an outcome", workflowId);
        }
        deliver(workflowId, wf -> wf.resume(nodeId, ra));
        LOG.info("Resume {} delivered to workflowId={} node={}", action, workflowId, nodeId);
    }

    private void deliver(String workflowId, java.util.function.Consumer<FlowWorkflow> call) {
        try {
            call.accept(client.newWorkflowStub(FlowWorkflow.class, workflowId));
        } catch (WorkflowNotFoundException e) {
            // Temporal reports a closed workflow the same way as a missing one; describe tells them apart.
            if (exists(workflowId)) {
                throw new ApiException(HttpStatus.CONFLICT, "workflow already finished", workflowId);
            }
            LOG.warn("Workflow not found: {}", workflowId);
            throw new ApiException(HttpStatus.NOT_FOUND, "workflow not found", workflowId);
        }
    }

    private boolean exists(String workflowId) {
        try {
            client.newUntypedWorkflowStub(workflowId).describe();
            return true;
        } catch (WorkflowNotFoundException e) {
            return false;
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    /**
     * RUNNING / PARKED (running with at least one node parked) / COMPLETED / FAILED / CANCELED / TERMINATED /
     * TIMED_OUT. {@code nodes} is the engine's per-node status query for the workflow itself (only PARKED/DONE;
     * the engine tracks no "current node"); a parked node inside a child workflow shows on that child's id.
     */
    public Map<String, Object> status(String workflowId) {
        require(WORKFLOW_ID, workflowId, "workflowId");
        WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
        WorkflowExecutionStatus st;
        try {
            st = stub.describe().getStatus();
        } catch (WorkflowNotFoundException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "workflow not found", workflowId);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new ApiException(HttpStatus.NOT_FOUND, "workflow not found", workflowId);
            }
            throw e;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workflowId", workflowId);
        String status = st.name().replace("WORKFLOW_EXECUTION_STATUS_", "");
        if (st == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING) {
            try {
                FlowState state = client.newWorkflowStub(FlowWorkflow.class, workflowId).query();
                out.put("nodes", state.nodeStatus());
                List<String> parked = new ArrayList<>();
                state.nodeStatus().forEach((n, s) -> {
                    if ("PARKED".equals(s)) {
                        parked.add(n);
                    }
                });
                if (!parked.isEmpty()) {
                    status = "PARKED";
                    out.put("parkedNodes", parked);
                }
            } catch (RuntimeException e) {
                LOG.warn("Query failed for workflowId={} ({})", workflowId, e.getClass().getSimpleName());
            }
        } else if (st == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED) {
            NodeResult r = stub.getResult(NodeResult.class);
            out.put("outcome", r.outcome());
            out.put("outputs", r.outputs());
        }
        out.put("status", status);
        return out;
    }
}
