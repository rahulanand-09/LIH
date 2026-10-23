package com.rupeek.flow.engine;

import com.rupeek.flow.task.TaskActivity;
import com.rupeek.flow.task.TaskResult;
import com.rupeek.flow.model.Edge;
import com.rupeek.flow.model.EndNode;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.OnError;
import com.rupeek.flow.model.OperationKind;
import com.rupeek.flow.model.SubflowNode;
import com.rupeek.flow.model.TaskDef;
import com.rupeek.flow.model.TaskNode;
import com.rupeek.flow.registry.activity.FetchTaskActivity;
import com.rupeek.flow.registry.activity.ResolveFlowActivity;
import com.rupeek.flow.registry.activity.ResolveTaskActivity;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6: version pinning is resolved per level, at that level's own start (not once for the whole tree), and a
 * runtime cycle check backstops the publish-time one.
 */
class VersionPinningAndCycleGuardTest {

    private static final String QUEUE = "flow-m6";

    private TestWorkflowEnvironment env;
    private Worker worker;
    private WorkflowClient client;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        worker = env.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(FlowWorkflowImpl.class);
        client = env.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    /** "leaf" (TASK, taskRef=leaf-op) -> "done" (END CHILD_DONE). */
    private static FlowDef childFlow() {
        return new FlowDef("child-pin", 1, "leaf", List.of(
                new TaskNode("leaf", OperationKind.MAP, "leaf-op", Map.of(), Map.of(), new OnError(1, "PARK")),
                new EndNode("done", "CHILD_DONE")
        ), List.of(new Edge("leaf", "OK", "done")));
    }

    /** "sub" (SUBFLOW -> child-pin) -> "end" (END PARENT_DONE). */
    private static FlowDef parentFlow() {
        return new FlowDef("parent-pin", 1, "sub", List.of(
                new SubflowNode("sub", "child-pin", Map.of(), new OnError(1, "PARK")),
                new EndNode("end", "PARENT_DONE")
        ), List.of(new Edge("sub", "CHILD_DONE", "end")));
    }

    @Test
    void eachLevelResolvesItsOwnVersionsIndependentlyAtItsOwnStart() {
        AtomicInteger resolveCalls = new AtomicInteger();
        AtomicInteger leafTaskVersion = new AtomicInteger(-1);
        worker.registerActivitiesImplementations(
                // Call N returns version N for whatever it was asked — proves two separate calls happen (one
                // per level, split across the flow- and task-ref activities since this flow has one of each) and
                // that a later level can legitimately see a different pinned version.
                (ResolveFlowActivity) refs -> {
                    int version = resolveCalls.incrementAndGet();
                    return refs.stream().collect(java.util.stream.Collectors.toMap(r -> r, r -> version));
                },
                (ResolveTaskActivity) refs -> {
                    int version = resolveCalls.incrementAndGet();
                    return refs.stream().collect(java.util.stream.Collectors.toMap(r -> r, r -> version));
                },
                (FetchTaskActivity) (ref, version) -> new TaskDef(ref, version, OperationKind.MAP, Map.of()),
                (TaskActivity) request -> {
                    leafTaskVersion.set(request.taskVersion());
                    return new TaskResult("OK", Map.of());
                });
        env.start();

        FlowWorkflow stub = client.newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId("pin-1").build());
        var result = stub.run(FlowRunRequest.root(parentFlow(), Map.of(), Map.of("child-pin", childFlow())));

        assertThat(result.outcome()).isEqualTo("PARENT_DONE");
        assertThat(resolveCalls).as("one resolution per level (parent, then child)").hasValue(2);
        // The child's own (second) resolution is what pinned "leaf-op" — not a single upfront resolve from the root.
        assertThat(leafTaskVersion).hasValue(2);
    }

    @Test
    void aSelfReferencingSubflowFailsFastInsteadOfRecursingForever() {
        FlowDef loopy = new FlowDef("loopy", 1, "sub", List.of(
                new SubflowNode("sub", "loopy", Map.of(), new OnError(1, "PARK")),
                new EndNode("end", "DONE")
        ), List.of(new Edge("sub", "DONE", "end")));
        worker.registerActivitiesImplementations((TaskActivity) request -> new TaskResult("OK", Map.of()),
                (ResolveFlowActivity) refs -> Map.of());
        env.start();

        FlowWorkflow stub = client.newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId("loopy-1").build());

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> stub.run(FlowRunRequest.root(loopy, Map.of(), Map.of("loopy", loopy))));

        assertThat(thrown).isInstanceOf(WorkflowFailedException.class);
        Throwable root = thrown;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root.getMessage()).contains("Cycle detected", "loopy");
    }
}
