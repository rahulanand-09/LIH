package com.rupeek.flow.registry;

import com.rupeek.flow.registry.activity.FetchFlowActivityImpl;
import com.rupeek.flow.registry.activity.ResolveFlowActivityImpl;

import com.rupeek.flow.model.Edge;
import com.rupeek.flow.model.EndNode;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.NodeResult;
import com.rupeek.flow.model.OnError;
import com.rupeek.flow.model.SubflowNode;
import com.rupeek.flow.engine.FlowRunRequest;
import com.rupeek.flow.engine.FlowWorkflow;
import com.rupeek.flow.engine.FlowWorkflowImpl;
import com.rupeek.flow.registry.store.InMemoryConfigStore;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SubflowNode.version()} pinning: a caller that declares an explicit version never floats to whatever
 * the registry currently calls latest, unlike an unpinned ref (proven already by {@link FlowVersioningTest}).
 * Two callers — one pinned to v1, one pinned to v2 of the SAME dependency flowRef — run CONCURRENTLY (both
 * started before either is awaited) against a registry where v2 is already the latest pointer, and neither
 * one drifts: the v1-pinned caller still gets v1, side by side with the v2-pinned caller getting v2.
 */
class FlowVersionPinningTest {

    private static final String QUEUE = "flow-pinning";

    private TestWorkflowEnvironment env;
    private WorkflowClient client;

    @BeforeEach
    void setUp() {
        InMemoryConfigStore store = new InMemoryConfigStore();
        StoreFlowSource flowSource = new StoreFlowSource(store);

        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(FlowWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                new ResolveFlowActivityImpl(new FlowRegistryService(flowSource)),
                new FetchFlowActivityImpl(flowSource));
        env.start();
        client = env.getWorkflowClient();

        FlowPublisher publisher = new FlowPublisher(store, Clock.systemUTC());
        publisher.publish(dep(1), "test", "v1");
        publisher.publish(dep(2), "test", "v2 -- becomes latest");
        // "latest" now points at v2. A v1-PINNED caller must still get v1 anyway.
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    private static FlowDef dep(int version) {
        return new FlowDef("toy.pinned-dependency", version, "done",
                List.of(new EndNode("done", "OK", Map.of("tag", "v" + version))), List.of());
    }

    /** "sub" (SUBFLOW -> toy.pinned-dependency, PINNED to pinnedVersion) -> "end". */
    private static FlowDef caller(int pinnedVersion) {
        return new FlowDef("caller-pinned-v" + pinnedVersion, 1, "sub", List.of(
                new SubflowNode("sub", "toy.pinned-dependency", Map.of(), new OnError(1, "PARK"), false, pinnedVersion),
                new EndNode("end", "CALLER_DONE", Map.of("tag", "$.sub.outputs.tag"))
        ), List.of(new Edge("sub", "OK", "end")));
    }

    private FlowWorkflow startAsync(String workflowId, FlowDef flow) {
        FlowWorkflow stub = client.newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId(workflowId).build());
        // Deliberately empty flat registry: "toy.pinned-dependency" must resolve through the real
        // FetchFlowActivity, by the PINNED version on the SubflowNode -- never through "latest".
        WorkflowClient.start(stub::run, FlowRunRequest.root(flow, Map.of(), Map.of()));
        return stub;
    }

    @Test
    void twoPinnedCallersRunCorrectlySideBySideNeitherOneDriftsToLatest() throws Exception {
        // Both started BEFORE either is awaited -- genuinely concurrent, not sequential.
        FlowWorkflow callerV1 = startAsync("pin-caller-v1", caller(1));
        FlowWorkflow callerV2 = startAsync("pin-caller-v2", caller(2));

        NodeResult v1Result = WorkflowStub.fromTyped(callerV1).getResult(10, TimeUnit.SECONDS, NodeResult.class);
        NodeResult v2Result = WorkflowStub.fromTyped(callerV2).getResult(10, TimeUnit.SECONDS, NodeResult.class);

        assertThat(v1Result.outcome()).isEqualTo("CALLER_DONE");
        assertThat(v1Result.outputs()).containsEntry("tag", "v1");   // NOT "v2", even though v2 is latest
        assertThat(v2Result.outcome()).isEqualTo("CALLER_DONE");
        assertThat(v2Result.outputs()).containsEntry("tag", "v2");
    }
}
