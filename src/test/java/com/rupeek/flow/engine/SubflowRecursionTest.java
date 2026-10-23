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
import com.rupeek.flow.registry.activity.ResolveFlowActivity;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2: a SUBFLOW node starts another whole flow as a Temporal child workflow. Proves the deterministic child id
 * scheme ({@code {parent}:{nodeId}}) and that the child's END status arrives as the parent node's outcome.
 */
class SubflowRecursionTest {

    private static final String QUEUE = "flow-m2";
    private static final String PARENT_ID = "parent-1";

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

    /** "leaf" (TASK) -> "done" (END, status CHILD_DONE). */
    private static FlowDef childFlow() {
        return new FlowDef("child-toy", 1, "leaf", List.of(
                new TaskNode("leaf", OperationKind.MAP, null, Map.of(), Map.of(), new OnError(1, "PARK")),
                new EndNode("done", "CHILD_DONE")
        ), List.of(
                new Edge("leaf", "OK", "done")
        ));
    }

    /** "sub" (SUBFLOW -> child-toy) -> "end" (END, status PARENT_DONE), on the child's own outcome label. */
    private static FlowDef parentFlow() {
        return new FlowDef("parent-toy", 1, "sub", List.of(
                new SubflowNode("sub", "child-toy", Map.of(), new OnError(1, "PARK")),
                new EndNode("end", "PARENT_DONE")
        ), List.of(
                new Edge("sub", "CHILD_DONE", "end")
        ));
    }

    @Test
    void childRunsUnderADeterministicIdAndItsEndStatusBecomesTheParentsOutcome() {
        worker.registerActivitiesImplementations((TaskActivity) request -> new TaskResult("OK", Map.of()),
                (ResolveFlowActivity) refs -> refs.stream().collect(java.util.stream.Collectors.toMap(r -> r, r -> 1)));
        env.start();

        WorkflowClient client = env.getWorkflowClient();
        FlowWorkflow parent = client.newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId(PARENT_ID).build());

        NodeResult result = parent.run(FlowRunRequest.root(parentFlow(), Map.of(), Map.of("child-toy", childFlow())));

        assertThat(result.outcome()).isEqualTo("PARENT_DONE");

        // The child ran under the deterministic id, as its own independently addressable workflow execution.
        WorkflowStub childStub = client.newUntypedWorkflowStub(PARENT_ID + ":sub");
        NodeResult childResult = childStub.getResult(NodeResult.class);
        assertThat(childResult.outcome()).isEqualTo("CHILD_DONE");
    }
}
