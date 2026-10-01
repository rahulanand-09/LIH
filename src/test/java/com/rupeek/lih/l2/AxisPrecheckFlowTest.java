package com.rupeek.lih.l2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The real axis-precheck.json flow on Temporal's in-memory server, with fake lender responses and sinks. */
class AxisPrecheckFlowTest {
    private static final String Q = "lender-axis";

    private TestWorkflowEnvironment env;
    private FlowWorkflow wf;
    private final List<String> sinkCalls = new ArrayList<>();
    private final Map<String, Object> statusResponse = new HashMap<>(); // what Axis dedupe_status "returns"

    @BeforeEach
    void setUp() throws Exception {
        env = TestWorkflowEnvironment.newInstance();
        Worker w = env.newWorker(Q);
        w.registerWorkflowImplementationTypes(FlowWorkflowImpl.class);

        Map<String, OperationDef> ops = new HashMap<>();
        ops.put("axis/dedupe", op("fake://dedupe", Map.of("applicantId", "data.applicantId")));
        ops.put("axis/timble", op("fake://timble", Map.of("referenceId", "reference_id")));
        ops.put("axis/status", op("fake://status", null));

        Map<String, ProtocolAdapter> adapters = Map.of("FAKE", (op, req) -> switch (op.url) {
            case "fake://dedupe" -> Map.of("data", Map.of("applicantId", "APP-1"));
            case "fake://timble" -> Map.of("reference_id", "TIMBLE-1");
            case "fake://status" -> statusResponse;
            default -> throw new IllegalStateException(op.url);
        });

        Map<String, StepActivitiesImpl.Sink> sinks = new HashMap<>();
        for (String s : List.of("precheckInit", "timbleStatus", "precheckStatus", "rupeekweb.verified", "rupeekweb.failed")) {
            sinks.put(s, vars -> sinkCalls.add(s));
        }
        w.registerActivitiesImplementations(new StepActivitiesImpl(ops, adapters, Map.of(), sinks));
        env.start();

        wf = env.getWorkflowClient().newWorkflowStub(FlowWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(Q).setWorkflowId("axis-precheck-C1-LR1").build());
        FlowRequest req = new FlowRequest();
        try (InputStream in = getClass().getResourceAsStream("/flows/axis-precheck.json")) {
            req.flow = new ObjectMapper().readValue(in, FlowDef.class);
        }
        req.lenderSlug = "axis";
        req.vars.put("customerId", "C1");
        req.vars.put("loanRequestId", "LR1");
        WorkflowClient.start(wf::run, req);
    }

    @AfterEach
    void tearDown() { env.close(); }

    @Test
    void approvesWhenEveryFlagIsYAndTimbleCallbackPassed() {
        // callback arrives first, status pull afterwards: same order as production
        Map<String, Object> cb = wf.event("timbleCallback", Map.of("name_match", "Y", "aadhar_match", "Y"));
        assertEquals(true, cb.get("timbleOk"));

        statusResponse.putAll(Map.of("dedupe", "Y", "pan", "Y", "ramp", "Y", "hunter", "Y"));
        Map<String, Object> st = wf.event("status", Map.of());
        assertEquals("SUCCESS", st.get("outcome"));

        Map<String, Object> result = WorkflowStub.fromTyped(wf).getResult(Map.class);
        assertEquals(true, result.get("done"));
        assertEquals("APP-1", ((Map<?, ?>) result.get("dedupe")).get("applicantId"));
        assertEquals(List.of("precheckInit", "timbleStatus", "precheckStatus", "rupeekweb.verified"), sinkCalls);
    }

    @Test
    void staysPendingWhileTimbleCallbackHasNotArrived_thenRejectsOnNameMismatch() {
        statusResponse.putAll(Map.of("dedupe", "Y", "pan", "Y", "ramp", "Y", "hunter", "Y"));
        Map<String, Object> pending = wf.event("status", Map.of());
        assertEquals("PENDING", pending.get("outcome")); // timble flag is WIP
        assertEquals(List.of("precheckInit", "precheckStatus"), sinkCalls);
        assertFalse((Boolean) wf.state().get("done"));

        Map<String, Object> cb = wf.event("timbleCallback", Map.of("name_match", "N", "aadhar_match", "Y"));
        assertEquals(false, cb.get("timbleOk")); // callback endpoint would answer 400 / status N from this

        Map<String, Object> rejected = wf.event("status", Map.of());
        assertEquals("FAILURE", rejected.get("outcome"));
        assertEquals("rupeekweb.failed", sinkCalls.get(sinkCalls.size() - 1));
        assertFalse(sinkCalls.contains("rupeekweb.verified"));
    }

    @Test
    void anyWipFlagKeepsItPending() {
        statusResponse.putAll(Map.of("dedupe", "Y", "pan", "WIP", "ramp", "Y", "hunter", "Y"));
        wf.event("timbleCallback", Map.of("name_match", "Y", "aadhar_match", "Y"));
        assertEquals("PENDING", wf.event("status", Map.of()).get("outcome"));
        assertFalse(sinkCalls.contains("rupeekweb.verified") || sinkCalls.contains("rupeekweb.failed"));
    }

    private static OperationDef op(String url, Map<String, String> extract) {
        OperationDef d = new OperationDef();
        d.protocol = "FAKE";
        d.url = url;
        d.extract = extract;
        return d;
    }
}
