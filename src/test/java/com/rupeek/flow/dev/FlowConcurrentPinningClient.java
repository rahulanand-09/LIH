package com.rupeek.flow.dev;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.NodeResult;
import com.rupeek.flow.registry.FlowDefLoader;
import com.rupeek.flow.workflow.FlowRunRequest;
import com.rupeek.flow.workflow.FlowWorkflow;
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
 * The real proof docs/flow-lld.md §9 was missing: NOT "run v1, publish v2, run again" (sequential — every
 * caller just floats to whatever's latest) but two callers PINNED to different, specific versions of the SAME
 * dependency flowRef, started CONCURRENTLY against the real standing {@link FlowWorkerDaemon} and its real
 * registry (which must already have both {@code axis-precheck-decomposed-coreAndStableId@1} and {@code @2}
 * published — true after the earlier {@code FlowVersioningDemoRunner}/{@code FlowPublishClient} runs). Neither
 * caller drifts to "latest": {@code caller-pinned-v1.json} always gets v1, {@code caller-pinned-v2.json} always
 * gets v2, side by side, at the same time, proven both by the printed results and by a per-case Mongo audit
 * check (only the v2-pinned case's loanRequestId should have the {@code precheckStatus: "v2"} audit row).
 */
public final class FlowConcurrentPinningClient {

    private static final String QUEUE = "lih-l1";

    public static void main(String[] args) throws Exception {
        String loanIdV1 = "LR-PIN-V1-" + System.currentTimeMillis();
        String loanIdV2 = "LR-PIN-V2-" + System.currentTimeMillis();

        WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget("127.0.0.1:7233").build());
        WorkflowClient client = WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace("default").build());

        FlowDef callerV1 = FlowDefLoader.loadFromClasspath("/flows-pinning/caller-pinned-v1.json");
        FlowDef callerV2 = FlowDefLoader.loadFromClasspath("/flows-pinning/caller-pinned-v2.json");

        // Both started BEFORE either is awaited -- genuinely concurrent, not sequential.
        FlowWorkflow stubV1 = start(client, "pin-v1-" + loanIdV1, callerV1, loanIdV1);
        FlowWorkflow stubV2 = start(client, "pin-v2-" + loanIdV2, callerV2, loanIdV2);
        System.out.println("Both started concurrently:");
        System.out.println("  http://localhost:8233/namespaces/default/workflows/pin-v1-" + loanIdV1 + " (pinned to macro v1)");
        System.out.println("  http://localhost:8233/namespaces/default/workflows/pin-v2-" + loanIdV2 + " (pinned to macro v2)");

        NodeResult resultV1 = WorkflowStub.fromTyped(stubV1).getResult(60, TimeUnit.SECONDS, NodeResult.class);
        NodeResult resultV2 = WorkflowStub.fromTyped(stubV2).getResult(60, TimeUnit.SECONDS, NodeResult.class);
        System.out.println("v1-pinned caller -> " + resultV1.outcome() + " " + resultV1.outputs());
        System.out.println("v2-pinned caller -> " + resultV2.outcome() + " " + resultV2.outputs());

        MongoClient mongo = MongoClients.create("mongodb://localhost:27018");
        MongoDatabase lendercore = mongo.getDatabase("lendercore");
        long v1CallerV2Tags = countVersionTags(lendercore, loanIdV1);
        long v2CallerV2Tags = countVersionTags(lendercore, loanIdV2);
        System.out.println("v1-pinned case's v2-audit-tag count: " + v1CallerV2Tags + " (expect 0 -- it never ran v2's new step)");
        System.out.println("v2-pinned case's v2-audit-tag count: " + v2CallerV2Tags + " (expect 1 -- it ran v2's new step, at the SAME TIME the v1-pinned case did not)");
        mongo.close();

        stubs.shutdown();
    }

    private static FlowWorkflow start(WorkflowClient client, String workflowId, FlowDef flow, String loanRequestId) {
        FlowWorkflow stub = client.newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId(workflowId)
                        .setWorkflowExecutionTimeout(Duration.ofMinutes(10)).build());
        Map<String, Object> inputs = Map.of("loanRequestId", loanRequestId, "runNo", 1);
        WorkflowClient.start(stub::run, FlowRunRequest.root(flow, inputs, Map.of()));
        return stub;
    }

    private static long countVersionTags(MongoDatabase db, String loanRequestId) {
        return db.getCollection("LenderCustomerPrecheckAudit").countDocuments(
                Filters.and(Filters.eq("loanRequestId", loanRequestId), Filters.eq("precheckStatus", "v2")));
    }

    private FlowConcurrentPinningClient() {
    }
}
