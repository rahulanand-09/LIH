package com.rupeek.flow.observability;

import ch.qos.logback.classic.Level;
import com.rupeek.flow.engine.FlowRunRequest;
import com.rupeek.flow.engine.FlowWorkflow;
import com.rupeek.flow.engine.FlowWorkflowImpl;
import com.rupeek.flow.model.Edge;
import com.rupeek.flow.model.EndNode;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.NodeResult;
import com.rupeek.flow.model.OnError;
import com.rupeek.flow.model.OperationKind;
import com.rupeek.flow.model.TaskNode;
import com.rupeek.flow.task.TaskActivity;
import com.rupeek.flow.task.TaskResult;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Workflow-side logs: present once, carrying workflow MDC, absent on replay; node-outcome metric counted once. */
class EngineObservabilityTest {

    private static final String QUEUE = "obs-engine";

    private TestWorkflowEnvironment env;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        env = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setWorkerFactoryOptions(WorkerFactoryOptions.newBuilder()
                        .setWorkerInterceptors(new LihWorkerInterceptor()).build())
                .build());
    }

    @AfterEach
    void tearDown() {
        env.close();
        Metrics.removeRegistry(registry);
    }

    private static FlowDef flow() {
        return new FlowDef("obs-toy", 7, "task", List.of(
                new TaskNode("task", OperationKind.MAP, null, Map.of("outcome", "OK"), Map.of(), new OnError(1, "PARK")),
                new EndNode("done", "DONE")),
                List.of(new Edge("task", "OK", "done")));
    }

    @Test
    void logsOnceWithContextAndNotOnReplay() throws Exception {
        Worker worker = env.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(FlowWorkflowImpl.class);
        worker.registerActivitiesImplementations((TaskActivity) request -> new TaskResult("OK", Map.of()));
        env.start();

        FlowWorkflow stub = env.getWorkflowClient().newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId("wf-obs").build());
        NodeResult result;
        FlowRunRequest request = FlowRunRequest.root(flow(), Map.of("loanRequestId", "LR-7"), Map.of());
        try (LogCapture logs = new LogCapture(Level.INFO)) {
            result = stub.run(request);
            assertThat(result.outcome()).isEqualTo("DONE");

            var exits = logs.events().stream().filter(e -> e.getFormattedMessage().startsWith("node exit")).toList();
            assertThat(exits).hasSize(1);
            var mdc = exits.get(0).getMDCPropertyMap();
            assertThat(mdc).containsEntry("workflowId", "wf-obs").containsEntry("flowRef", "obs-toy@7")
                    .containsEntry("nodeId", "task").containsEntry("loanRequestId", "LR-7").containsKey("runId");
            assertThat(exits.get(0).getFormattedMessage()).contains("outcome=OK", "next=done");
            assertThat(logs.text()).contains("flow start flowRef=obs-toy@7", "flow end flowRef=obs-toy@7 status=DONE");
        }

        // Replay the recorded history: the workflow re-executes, but nothing may be logged or counted again.
        WorkflowExecutionHistory history = env.getWorkflowClient().fetchHistory("wf-obs");
        try (LogCapture replayLogs = new LogCapture(Level.DEBUG)) {
            WorkflowReplayer.replayWorkflowExecution(history, FlowWorkflowImpl.class);
            assertThat(replayLogs.events().stream().filter(e -> e.getLoggerName().endsWith("FlowWorkflowImpl")))
                    .as("workflow log lines emitted during replay").isEmpty();
        }
        assertThat(registry.get(FlowMetrics.NODE_OUTCOMES).tags("flowRef", "obs-toy", "node", "task", "outcome", "OK")
                .counter().count()).isEqualTo(1.0);
    }
}
