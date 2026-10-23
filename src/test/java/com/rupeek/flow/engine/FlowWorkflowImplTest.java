package com.rupeek.flow.engine;

import com.rupeek.flow.task.TaskActivity;
import com.rupeek.flow.task.TaskResult;
import com.rupeek.flow.model.Edge;
import com.rupeek.flow.model.EndNode;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.OnError;
import com.rupeek.flow.model.OperationKind;
import com.rupeek.flow.model.TaskNode;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** M1: TASK + END only — proves edge routing by outcome label, with no shared mutable state across nodes. */
class FlowWorkflowImplTest {

    private static final String QUEUE = "flow-m1";

    private TestWorkflowEnvironment env;
    private Worker worker;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        worker = env.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(FlowWorkflowImpl.class);
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    private FlowWorkflow stub() {
        return env.getWorkflowClient().newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).build());
    }

    /** start -> "greet" (TASK) -> "end" (END), via a single "OK" edge. */
    private static FlowDef twoNodeFlow() {
        return new FlowDef("toy", 1, "greet", List.of(
                new TaskNode("greet", OperationKind.MAP, null, Map.of(),
                        Map.of("name", "$.in.name"), new OnError(1, "PARK")),
                new EndNode("end", "DONE")
        ), List.of(
                new Edge("greet", "OK", "end")
        ));
    }

    @Test
    void routesThroughASingleTaskToEnd() {
        worker.registerActivitiesImplementations((TaskActivity) request -> {
            assertThat(request.inputs()).containsEntry("name", "Rahul");
            return new TaskResult("OK", Map.of("greeting", "hello Rahul"));
        });
        env.start();

        var result = stub().run(FlowRunRequest.root(twoNodeFlow(), Map.of("name", "Rahul"), Map.of()));

        assertThat(result.outcome()).isEqualTo("DONE");
    }

    @Test
    void picksTheEdgeMatchingTheOutcomeLabel() {
        FlowDef flow = new FlowDef("toy-branch", 1, "check", List.of(
                new TaskNode("check", OperationKind.MAP, null, Map.of(), Map.of(), new OnError(1, "PARK")),
                new EndNode("approved", "APPROVED"),
                new EndNode("rejected", "REJECTED")
        ), List.of(
                new Edge("check", "OK", "approved"),
                new Edge("check", "FAIL", "rejected")
        ));
        worker.registerActivitiesImplementations((TaskActivity) request -> new TaskResult("FAIL", Map.of()));
        env.start();

        var result = stub().run(FlowRunRequest.root(flow, Map.of(), Map.of()));

        assertThat(result.outcome()).isEqualTo("REJECTED");
    }

    @Test
    void onesNodesOutputFeedsTheNextNodesInput() {
        FlowDef flow = new FlowDef("toy-chain", 1, "first", List.of(
                new TaskNode("first", OperationKind.MAP, null, Map.of(), Map.of(), new OnError(1, "PARK")),
                new TaskNode("second", OperationKind.MAP, null, Map.of(),
                        Map.of("fromFirst", "$.first.outputs.value"), new OnError(1, "PARK")),
                new EndNode("end", "DONE")
        ), List.of(
                new Edge("first", "OK", "second"),
                new Edge("second", "OK", "end")
        ));
        AtomicReference<Map<String, Object>> secondNodeInputs = new AtomicReference<>();
        worker.registerActivitiesImplementations((TaskActivity) request -> {
            if ("first".equals(request.nodeId())) {
                return new TaskResult("OK", Map.of("value", "from-first-node"));
            }
            secondNodeInputs.set(request.inputs());
            return new TaskResult("OK", Map.of());
        });
        env.start();

        stub().run(FlowRunRequest.root(flow, Map.of(), Map.of()));

        assertThat(secondNodeInputs.get()).containsEntry("fromFirst", "from-first-node");
    }
}
