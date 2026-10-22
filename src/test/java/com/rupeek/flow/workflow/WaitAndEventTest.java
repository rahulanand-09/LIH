package com.rupeek.flow.workflow;

import com.rupeek.flow.activity.TaskActivity;
import com.rupeek.flow.activity.TaskResult;
import com.rupeek.flow.model.Edge;
import com.rupeek.flow.model.EndNode;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.NodeResult;
import com.rupeek.flow.model.OnError;
import com.rupeek.flow.model.OperationKind;
import com.rupeek.flow.model.TaskNode;
import com.rupeek.flow.model.WaitNode;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** M5: a WAIT node parks for a named external event — buffered independent of where the graph walk currently is. */
class WaitAndEventTest {

    private static final String QUEUE = "flow-m5";

    private TestWorkflowEnvironment env;
    private Worker worker;
    private WorkflowClient client;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        worker = env.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(FlowWorkflowImpl.class);
        worker.registerActivitiesImplementations((TaskActivity) request -> new TaskResult("OK", Map.of()));
        client = env.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    /** "before" (TASK) -> "wait" (WAIT for "cb", no timeout) -> "done" (END DONE) on RECEIVED. */
    private static FlowDef flowWithoutTimeout() {
        return new FlowDef("wait-toy", 1, "before", List.of(
                new TaskNode("before", OperationKind.MAP, null, Map.of(), Map.of(), new OnError(1, "PARK")),
                new WaitNode("wait", "cb", null, null),
                new EndNode("done", "DONE")
        ), List.of(
                new Edge("before", "OK", "wait"),
                new Edge("wait", "RECEIVED", "done")
        ));
    }

    @Test
    void anEventSentBeforeTheWaitNodeIsReachedIsStillPickedUpThere() throws TimeoutException {
        env.start();
        FlowWorkflow stub = client.newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId("wait-1").build());
        WorkflowClient.start(stub::run, FlowRunRequest.root(flowWithoutTimeout(), Map.of(), Map.of()));

        // Sent essentially immediately — almost certainly before the "before" TASK's activity round-trip
        // completes and the walk even reaches "wait". The buffering must not care either way.
        stub.event("cb", Map.of("flag", "Y"));

        NodeResult result = WorkflowStub.fromTyped(stub).getResult(10, TimeUnit.SECONDS, NodeResult.class);
        assertThat(result.outcome()).isEqualTo("DONE");
    }

    /** "wait" (WAIT for "cb", 1s timeout, onTimeout TIMED_OUT) -> "timedOut" (END TIMED_OUT). */
    private static FlowDef flowWithTimeout() {
        return new FlowDef("wait-timeout-toy", 1, "wait", List.of(
                new WaitNode("wait", "cb", Duration.ofSeconds(1), "TIMED_OUT"),
                new EndNode("timedOut", "TIMED_OUT")
        ), List.of(
                new Edge("wait", "TIMED_OUT", "timedOut")
        ));
    }

    @Test
    void noEventWithinTheTimeoutRoutesOnTheConfiguredTimeoutOutcome() throws TimeoutException {
        env.start();
        FlowWorkflow stub = client.newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId("wait-2").build());

        NodeResult result = stub.run(FlowRunRequest.root(flowWithTimeout(), Map.of(), Map.of()));

        assertThat(result.outcome()).isEqualTo("TIMED_OUT");
    }
}
