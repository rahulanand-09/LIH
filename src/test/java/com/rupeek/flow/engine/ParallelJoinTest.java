package com.rupeek.flow.engine;

import com.rupeek.flow.task.TaskActivity;
import com.rupeek.flow.task.TaskResult;
import com.rupeek.flow.model.Edge;
import com.rupeek.flow.model.EndNode;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.JoinNode;
import com.rupeek.flow.model.NodeResult;
import com.rupeek.flow.model.OnError;
import com.rupeek.flow.model.OperationKind;
import com.rupeek.flow.model.ParallelNode;
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

/** M4: PARALLEL fans out, JOIN("ALL") waits — a parked branch holds the join open without failing its siblings. */
class ParallelJoinTest {

    private static final String QUEUE = "flow-m4";

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

    /** fanout (PARALLEL a,b,c) -> join (JOIN ALL a,b,c) -> end (END ALL_DONE). */
    private static FlowDef flow() {
        return new FlowDef("fanout-toy", 1, "fanout", List.of(
                new ParallelNode("fanout", List.of("a", "b", "c")),
                new TaskNode("a", OperationKind.MAP, null, Map.of(), Map.of(), new OnError(1, "PARK")),
                new TaskNode("b", OperationKind.MAP, null, Map.of(), Map.of(), new OnError(1, "PARK")),
                new TaskNode("c", OperationKind.MAP, null, Map.of(), Map.of(), new OnError(1, "PARK")),
                new JoinNode("join", "ALL", List.of("a", "b", "c")),
                new EndNode("end", "ALL_DONE")
        ), List.of(
                new Edge("fanout", null, "join"),
                new Edge("join", null, "end")
        ));
    }

    private static FlowState awaitState(FlowWorkflow stub, java.util.function.Predicate<FlowState> ready) {
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
        return fail("branches never reached the expected state: " + stub.query());
    }

    @Test
    void aParkedBranchHoldsTheJoinOpenWithoutCancellingItsSiblingsThenJoinFiresOnce() throws TimeoutException {
        AtomicInteger cAttempts = new AtomicInteger();
        worker.registerActivitiesImplementations((TaskActivity) request -> {
            if ("c".equals(request.nodeId()) && cAttempts.incrementAndGet() == 1) {
                throw ApplicationFailure.newFailure("boom", "TECHNICAL");
            }
            return new TaskResult("OK", Map.of());
        });
        env.start();

        FlowWorkflow stub = client.newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId("fanout-1").build());
        WorkflowClient.start(stub::run, FlowRunRequest.root(flow(), Map.of(), Map.of()));

        // Siblings a and b complete and sit idle; c parks. None of this fails or cancels the others — the join
        // (not yet reached below) can only ever unblock once "c" is in `results` too, which happens solely via
        // the resume() call further down, so there is no path by which it could have fired already.
        FlowState state = awaitState(stub, s -> "PARKED".equals(s.nodeStatus().get("c")));
        assertThat(state.nodeStatus()).containsEntry("a", "DONE").containsEntry("b", "DONE");

        stub.resume("c", ResumeAction.retry());

        NodeResult result = WorkflowStub.fromTyped(stub).getResult(10, TimeUnit.SECONDS, NodeResult.class);
        assertThat(result.outcome()).isEqualTo("ALL_DONE");
        assertThat(cAttempts).hasValue(2);
    }
}
