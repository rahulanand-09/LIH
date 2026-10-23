package com.rupeek.flow.dev;

import com.rupeek.flow.model.NodeResult;
import com.rupeek.flow.registry.FlowDefLoader;
import com.rupeek.flow.engine.FlowRunRequest;
import com.rupeek.flow.engine.FlowWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A pure Temporal CLIENT — starts/signals one case against the real, already-running {@link FlowWorkerDaemon}.
 * Registers no worker, starts no Mongo/WireMock: all of that already exists on the daemon side. This is the
 * "submit work" half of the split; the daemon is the "do the work" half, same shape a real deployment has.
 */
public final class FlowTriggerClient {

    private static final String DEFAULT_QUEUE = "lih-l1";

    public static void main(String[] args) throws Exception {
        String loanRequestId = args.length > 0 ? args[0] : "LR-DAEMON-" + System.currentTimeMillis();
        String queue = args.length > 1 ? args[1] : DEFAULT_QUEUE;

        WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget("127.0.0.1:7233").build());
        WorkflowClient client = WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace("default").build());

        // Empty on purpose: every SUBFLOW ref (and every TASK taskRef inside them) resolves through the
        // daemon's real Mongo-backed registry (FetchFlowActivity/FetchTaskActivity), not an in-process shortcut
        // -- so this run exercises the exact same resolve/fetch/validate path production would.
        Map<String, com.rupeek.flow.model.FlowDef> registry = Map.of();
        var top = FlowDefLoader.loadFromClasspath("/flow-config/flows/axis-precheck-decomposed.json");

        String workflowId = "axis-precheck-decomposed-" + loanRequestId;
        FlowWorkflow stub = client.newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(queue).setWorkflowId(workflowId)
                        .setWorkflowExecutionTimeout(Duration.ofHours(1)).build());
        Map<String, Object> inputs = Map.of("loanRequestId", loanRequestId, "runNo", 1, "loanProcess", "fresh");
        WorkflowClient.start(stub::run, FlowRunRequest.root(top, inputs, registry));
        System.out.println("Started " + workflowId + " -- http://localhost:8233/namespaces/default/workflows/" + workflowId);

        stub.event("timbleCallback", Map.of("product_id", "P1", "customer_id", "CUST-TEST-1",
                "timble_status", "COMPLETED", "name_match", "Y", "aadhar_match", "Y"));

        NodeResult result = WorkflowStub.fromTyped(stub).getResult(60, TimeUnit.SECONDS, NodeResult.class);
        System.out.println("RESULT: " + result.outcome() + " " + result.outputs());

        stubs.shutdown();
    }

    private FlowTriggerClient() {
    }
}
