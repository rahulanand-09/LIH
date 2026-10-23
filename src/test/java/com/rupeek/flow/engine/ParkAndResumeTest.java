package com.rupeek.flow.engine;

import com.rupeek.flow.task.TaskActivity;
import com.rupeek.flow.task.TaskResult;
import com.rupeek.flow.model.Edge;
import com.rupeek.flow.model.EndNode;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.NodeResult;
import com.rupeek.flow.model.OnError;
import com.rupeek.flow.model.OperationKind;
import com.rupeek.flow.model.SubflowNode;
import com.rupeek.flow.model.TaskNode;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** M3: a node that exhausts its retries parks (the workflow itself doesn't fail) and waits for an operator. */
class ParkAndResumeTest {

    private static final String QUEUE = "flow-m3";

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

    /** "task" (TASK, single attempt) -> "done" (END DONE) on OK, -> "skipped" (END SKIPPED) on a synthetic SKIP. */
    private static FlowDef flow() {
        return new FlowDef("park-toy", 1, "task", List.of(
                new TaskNode("task", OperationKind.MAP, null, Map.of(), Map.of(), new OnError(1, "PARK")),
                new EndNode("done", "DONE"),
                new EndNode("skipped", "SKIPPED")
        ), List.of(
                new Edge("task", "OK", "done"),
                new Edge("task", "SYNTHETIC", "skipped")
        ));
    }

    private FlowWorkflow startAsync(String workflowId, FlowRunRequest request) {
        FlowWorkflow stub = client.newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId(workflowId).build());
        WorkflowClient.start(stub::run, request);
        return stub;
    }

    /**
     * Polls a workflow's query() from the test side (plain client code, not workflow code) until a predicate
     * holds. Tolerates the target not existing yet — e.g. a child workflow a SUBFLOW node is about to start.
     */
    private static FlowState awaitState(FlowWorkflow stub, java.util.function.Predicate<FlowState> ready) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            FlowState state;
            try {
                state = stub.query();
            } catch (RuntimeException notYetStarted) {
                state = null;
            }
            if (state != null && ready.test(state)) {
                return state;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        return fail("node never reached the expected state");
    }

    @Test
    void parksOnExhaustedRetriesThenRetryResumesIt() throws TimeoutException {
        AtomicInteger attempts = new AtomicInteger();
        worker.registerActivitiesImplementations((TaskActivity) request -> {
            if (attempts.incrementAndGet() == 1) {
                throw ApplicationFailure.newFailure("boom", "TECHNICAL");
            }
            return new TaskResult("OK", Map.of());
        });
        env.start();

        FlowWorkflow stub = startAsync("park-1", FlowRunRequest.root(flow(), Map.of(), Map.of()));
        awaitState(stub, s -> "PARKED".equals(s.nodeStatus().get("task")));

        stub.resume("task", ResumeAction.retry());

        NodeResult result = WorkflowStub.fromTyped(stub).getResult(10, TimeUnit.SECONDS, NodeResult.class);
        assertThat(result.outcome()).isEqualTo("DONE");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void parksThenSkipSynthesizesTheGivenOutcome() throws TimeoutException {
        worker.registerActivitiesImplementations((TaskActivity) request -> {
            throw ApplicationFailure.newFailure("always fails", "PERMANENT");
        });
        env.start();

        FlowWorkflow stub = startAsync("park-2", FlowRunRequest.root(flow(), Map.of(), Map.of()));
        awaitState(stub, s -> "PARKED".equals(s.nodeStatus().get("task")));

        stub.resume("task", ResumeAction.skip("SYNTHETIC"));

        NodeResult result = WorkflowStub.fromTyped(stub).getResult(10, TimeUnit.SECONDS, NodeResult.class);
        assertThat(result.outcome()).isEqualTo("SKIPPED");
    }

    /** "leaf" (TASK) -> "done" (END LEAF_DONE). */
    private static FlowDef childFlow() {
        return new FlowDef("child-park", 1, "leaf", List.of(
                new TaskNode("leaf", OperationKind.MAP, null, Map.of(), Map.of(), new OnError(1, "PARK")),
                new EndNode("done", "LEAF_DONE")
        ), List.of(new Edge("leaf", "OK", "done")));
    }

    /** "sub" (SUBFLOW -> child-park) -> "end" (END PARENT_DONE). */
    private static FlowDef parentFlow() {
        return new FlowDef("parent-park", 1, "sub", List.of(
                new SubflowNode("sub", "child-park", Map.of(), new OnError(1, "PARK")),
                new EndNode("end", "PARENT_DONE")
        ), List.of(new Edge("sub", "LEAF_DONE", "end")));
    }

    @Test
    void aParkedGrandchildIsResumedDirectlyByTraversalNotThroughTheParent() throws TimeoutException {
        AtomicInteger attempts = new AtomicInteger();
        worker.registerActivitiesImplementations((TaskActivity) request -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw ApplicationFailure.newFailure("boom", "TECHNICAL");
                    }
                    return new TaskResult("OK", Map.of());
                },
                (com.rupeek.flow.registry.activity.ResolveFlowActivity) refs ->
                        refs.stream().collect(java.util.stream.Collectors.toMap(r -> r, r -> 1)));
        env.start();

        String parentId = "parent-park-1";
        FlowWorkflow parent = startAsync(parentId,
                FlowRunRequest.root(parentFlow(), Map.of(), Map.of("child-park", childFlow())));

        // The operator never touches the parent: it addresses the grandchild's own deterministic id directly.
        FlowWorkflow child = client.newWorkflowStub(FlowWorkflow.class, parentId + ":sub");
        awaitState(child, s -> "PARKED".equals(s.nodeStatus().get("leaf")));

        child.resume("leaf", ResumeAction.retry());

        NodeResult result = WorkflowStub.fromTyped(parent).getResult(10, TimeUnit.SECONDS, NodeResult.class);
        assertThat(result.outcome()).isEqualTo("PARENT_DONE");
    }
}
