package com.rupeek.flow.worker;

import com.rupeek.flow.config.FlowProperties;

import com.rupeek.flow.task.TaskActivityImpl;
import com.rupeek.flow.registry.activity.FetchFlowActivityImpl;
import com.rupeek.flow.registry.activity.FetchTaskActivityImpl;
import com.rupeek.flow.registry.activity.ResolveFlowActivityImpl;
import com.rupeek.flow.registry.activity.ResolveTaskActivityImpl;
import com.rupeek.flow.engine.FlowWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.TimeUnit;

/**
 * The flow engine's own standing worker, on its own task queue (default {@code lih-l2}). Registers the
 * workflow implementation plus every activity a node might call: flow-level registry activities
 * (`resolveFlow`/`fetchFlow`, for SUBFLOW nodes), task-level registry activities (`resolveTask`/`fetchTask`, for
 * a TASK node's `taskRef` — see docs/flow-lld.md §16), and `taskActivity` (the native HTTP/MONGO/MAP/GEN
 * dispatcher that actually executes a fetched task's `fields`). Replaces the test-only {@code FlowWorkerDaemon}
 * this project used while proving out macros/versioning: same connection, registered here as real application
 * wiring instead of a one-off {@code main()} under {@code src/test}.
 */
public final class FlowTemporalWorker implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(FlowTemporalWorker.class);

    private final FlowProperties.Temporal settings;
    private final ResolveFlowActivityImpl resolveFlow;
    private final FetchFlowActivityImpl fetchFlow;
    private final TaskActivityImpl taskActivity;
    private final ResolveTaskActivityImpl resolveTask;
    private final FetchTaskActivityImpl fetchTask;
    private WorkflowServiceStubs stubs;
    private WorkerFactory factory;
    private volatile boolean running;

    public FlowTemporalWorker(FlowProperties.Temporal settings, ResolveFlowActivityImpl resolveFlow,
                              FetchFlowActivityImpl fetchFlow, TaskActivityImpl taskActivity,
                              ResolveTaskActivityImpl resolveTask, FetchTaskActivityImpl fetchTask) {
        this.settings = settings;
        this.resolveFlow = resolveFlow;
        this.fetchFlow = fetchFlow;
        this.taskActivity = taskActivity;
        this.resolveTask = resolveTask;
        this.fetchTask = fetchTask;
    }

    @Override
    public void start() {
        stubs = com.rupeek.flow.config.TemporalConnection.stubs(settings);
        WorkflowClient client = WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace(settings.getNamespace()).build());
        factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(settings.getTaskQueue(), WorkerOptions.newBuilder()
                .setMaxConcurrentActivityExecutionSize(settings.getMaxConcurrentActivities())
                .build());
        worker.registerWorkflowImplementationTypes(FlowWorkflowImpl.class);
        worker.registerActivitiesImplementations(resolveFlow, fetchFlow, taskActivity, resolveTask, fetchTask);
        factory.start();
        running = true;
        LOG.info("Flow worker polling task queue '{}' on {} (namespace {})", settings.getTaskQueue(),
                settings.getTarget(), settings.getNamespace());
    }

    @Override
    public void stop() {
        running = false;
        if (factory != null) {
            factory.shutdown();
            factory.awaitTermination(60, TimeUnit.SECONDS);
        }
        if (stubs != null) {
            stubs.shutdown();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
