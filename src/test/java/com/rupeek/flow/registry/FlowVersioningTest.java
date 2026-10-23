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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real flow versioning, end to end: {@code ResolveFlowActivity}/{@code FetchFlowActivity} backed by the SAME
 * registry (docs/flow-lld.md §9) a recipe's own {@code RegistryActivity} already uses, just a third collection
 * ({@code DocType.FLOW}) — not the identity "always 1" placeholder every test/demo before this used. The caller
 * (the parent flow) is published ONCE and never changes; publishing a new macro/subflow version and re-running
 * is enough for the caller to pick it up, because the flowRef is deliberately left OUT of the flat fixture
 * registry (so {@link FlowRunRequest}'s real fallback — {@code FetchFlowActivity}, resolved by whatever version
 * {@code resolveVersions} pinned — is the only way it resolves at all).
 */
class FlowVersioningTest {

    private static final String QUEUE = "flow-versioning";

    private TestWorkflowEnvironment env;
    private WorkflowClient client;
    private InMemoryConfigStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryConfigStore();
        StoreFlowSource flowSource = new StoreFlowSource(store);

        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(FlowWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                new ResolveFlowActivityImpl(new FlowRegistryService(flowSource)),
                new FetchFlowActivityImpl(flowSource));
        env.start();
        client = env.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    /** v1: the toy sub flow's EndNode tags its output "v1". */
    private static FlowDef subV1() {
        return new FlowDef("toy.versioned", 1, "done",
                List.of(new EndNode("done", "OK", Map.of("tag", "v1"))), List.of());
    }

    /** v2: same flowRef, same shape, different tag — simulating a real behaviour change. */
    private static FlowDef subV2() {
        return new FlowDef("toy.versioned", 2, "done",
                List.of(new EndNode("done", "OK", Map.of("tag", "v2"))), List.of());
    }

    /** Never changes across this whole test — not touched when v2 is published. */
    private static FlowDef parent() {
        return new FlowDef("versioning-parent", 1, "sub", List.of(
                new SubflowNode("sub", "toy.versioned", Map.of(), new OnError(1, "PARK")),
                new EndNode("end", "PARENT_DONE", Map.of("tag", "$.sub.outputs.tag"))
        ), List.of(new Edge("sub", "OK", "end")));
    }

    private NodeResult run(String workflowId) throws TimeoutException {
        FlowWorkflow stub = client.newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId(workflowId).build());
        // Deliberately an EMPTY flat registry: "toy.versioned" has no fixed answer here at all, so the only way
        // this resolves is the real FetchFlowActivity path, by whatever version resolveVersions actually pins.
        WorkflowClient.start(stub::run, FlowRunRequest.root(parent(), Map.of(), Map.of()));
        return WorkflowStub.fromTyped(stub).getResult(10, TimeUnit.SECONDS, NodeResult.class);
    }

    @Test
    void resolvesWhateverIsCurrentlyLatestWithoutTheCallerChangingAtAll() throws TimeoutException {
        FlowPublisher publisher = new FlowPublisher(store, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        publisher.publish(subV1(), "test", "initial publish");
        NodeResult v1Result = run("versioning-1");
        assertThat(v1Result.outcome()).isEqualTo("PARENT_DONE");
        assertThat(v1Result.outputs()).containsEntry("tag", "v1");

        publisher.publish(subV2(), "test", "bump to v2");
        NodeResult v2Result = run("versioning-2");
        assertThat(v2Result.outcome()).isEqualTo("PARENT_DONE");
        assertThat(v2Result.outputs()).containsEntry("tag", "v2");
    }

    @Test
    void publishingTheSameVersionTwiceIsRejectedVersionsAreImmutable() {
        FlowPublisher publisher = new FlowPublisher(store, Clock.systemUTC());
        publisher.publish(subV1(), "test", "first");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> publisher.publish(subV1(), "test", "again"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }
}
