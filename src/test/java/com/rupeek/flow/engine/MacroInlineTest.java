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
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * A {@code SubflowNode} with {@code inline=true} ("macro"): a reusable step-pattern spliced into THIS workflow
 * execution via a plain recursive interpreter call, not a Temporal child workflow. Two things a real SUBFLOW
 * doesn't give you, proven here: (1) the macro's internal nodes are visible in the CALLER's own {@code query()},
 * prefixed by the call site id — not hidden inside a separate child workflow's own state; (2) a parked node
 * inside the macro is resumed by signalling this same workflow directly, with the prefixed key, no traversal to
 * a different workflow id at all (there isn't one).
 */
class MacroInlineTest {

    private static final String QUEUE = "flow-macro";

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

    /** The reusable pattern: "a" -> "b" -> END OK, handing "b"'s result back as the macro's own output. */
    private static FlowDef macro() {
        return new FlowDef("common.fetchAndStableId", 1, "a", List.of(
                new TaskNode("a", OperationKind.MAP, null, Map.of(), Map.of(), new OnError(1, "PARK")),
                new TaskNode("b", OperationKind.MAP, null, Map.of(), Map.of(), new OnError(1, "PARK")),
                new EndNode("done", "OK", Map.of("value", "$.b.outputs.value"))
        ), List.of(
                new Edge("a", "OK", "b"),
                new Edge("b", "OK", "done")
        ));
    }

    /** "fetchStable" (SUBFLOW -> macro(), inline) -> "end" (END PARENT_DONE), forwarding the macro's output. */
    private static FlowDef parent() {
        return new FlowDef("macro-caller", 1, "fetchStable", List.of(
                new SubflowNode("fetchStable", "common.fetchAndStableId", Map.of(), new OnError(1, "PARK"), true),
                new EndNode("end", "PARENT_DONE", Map.of("stableId", "$.fetchStable.outputs.value"))
        ), List.of(new Edge("fetchStable", "OK", "end")));
    }

    private FlowWorkflow startAsync(String workflowId, FlowRunRequest request) {
        FlowWorkflow stub = client.newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId(workflowId).build());
        WorkflowClient.start(stub::run, request);
        return stub;
    }

    /**
     * The parent flow's one SUBFLOW node (inline or not) still has a {@code flowRef} this engine's own
     * placeholder version-resolution activity must resolve at the parent's own start (§9) -- same
     * requirement a real child-workflow SUBFLOW has, omitting it is a silent hang, not a visible error.
     */
    private static com.rupeek.flow.registry.activity.ResolveFlowActivity resolveFlowVersions() {
        return refs -> refs.stream().collect(java.util.stream.Collectors.toMap(r -> r, r -> 1));
    }

    private static FlowState awaitState(FlowWorkflow stub, Predicate<FlowState> ready) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            FlowState state = stub.query();
            if (ready.test(state)) {
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
    void anInlineMacroRunsInProcessAndItsNodesAreVisibleInTheCallersOwnQuery() throws TimeoutException {
        worker.registerActivitiesImplementations((TaskActivity) request ->
                        new TaskResult("OK", Map.of("value", "STABLE-123")),
                resolveFlowVersions());
        env.start();

        FlowWorkflow stub = startAsync("macro-1",
                FlowRunRequest.root(parent(), Map.of(), Map.of("common.fetchAndStableId", macro())));

        NodeResult result = WorkflowStub.fromTyped(stub).getResult(10, TimeUnit.SECONDS, NodeResult.class);
        assertThat(result.outcome()).isEqualTo("PARENT_DONE");
        assertThat(result.outputs()).containsEntry("stableId", "STABLE-123");

        // The macro's OWN internal nodes ("a", "b") show up directly in the CALLER's query(), prefixed by the
        // call site id ("fetchStable.") -- a real SUBFLOW would never expose a child's internals this way, since
        // they belong to an entirely separate workflow execution.
        FlowState state = stub.query();
        assertThat(state.nodeStatus()).containsEntry("fetchStable.a", "DONE");
        assertThat(state.nodeStatus()).containsEntry("fetchStable.b", "DONE");
        assertThat(state.nodeStatus()).containsEntry("fetchStable", "DONE");
    }

    @Test
    void aParkedNodeInsideAnInlineMacroIsResumedOnTheSameWorkflowIdNoChildToTraverseTo() throws TimeoutException {
        AtomicInteger attempts = new AtomicInteger();
        worker.registerActivitiesImplementations((TaskActivity) request -> {
                    if ("b".equals(request.nodeId()) && attempts.incrementAndGet() == 1) {
                        throw ApplicationFailure.newFailure("boom", "TECHNICAL");
                    }
                    return new TaskResult("OK", Map.of("value", "STABLE-456"));
                },
                resolveFlowVersions());
        env.start();

        FlowWorkflow stub = startAsync("macro-2",
                FlowRunRequest.root(parent(), Map.of(), Map.of("common.fetchAndStableId", macro())));

        awaitState(stub, s -> "PARKED".equals(s.nodeStatus().get("fetchStable.b")));

        // No child workflow id to find -- the parked node is a key in THIS workflow's own pendingResume map.
        stub.resume("fetchStable.b", ResumeAction.retry());

        NodeResult result = WorkflowStub.fromTyped(stub).getResult(10, TimeUnit.SECONDS, NodeResult.class);
        assertThat(result.outcome()).isEqualTo("PARENT_DONE");
        assertThat(result.outputs()).containsEntry("stableId", "STABLE-456");
    }
}
