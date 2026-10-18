package com.rupeek.l1.worker;

import com.rupeek.l1.dev.L1ProbeWorkflowImpl;
import com.rupeek.l1.dev.ReferenceAxisPrecheckWorkflowImpl;
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
 * Polls the L1 task queue and runs {@code RunTask}. Started after the application context is ready (config is
 * already compiled by then); on shutdown it stops polling and gives running activities up to 60 seconds.
 */
public final class L1TemporalWorker implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(L1TemporalWorker.class);

    private final L1Properties.Temporal settings;
    private final TaskActivityImpl activity;
    private final RegistryActivityImpl registry;
    private final L1Properties.Dev dev;
    private WorkflowServiceStubs stubs;
    private WorkerFactory factory;
    private volatile boolean running;

    public L1TemporalWorker(L1Properties.Temporal settings, TaskActivityImpl activity, RegistryActivityImpl registry,
                            L1Properties.Dev dev) {
        this.settings = settings;
        this.activity = activity;
        this.registry = registry;
        this.dev = dev;
    }

    @Override
    public void start() {
        stubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(settings.getTarget()).build());
        WorkflowClient client = WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace(settings.getNamespace()).build());
        factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(settings.getTaskQueue(), WorkerOptions.newBuilder()
                .setMaxConcurrentActivityExecutionSize(settings.getMaxConcurrentActivities())
                .build());
        worker.registerActivitiesImplementations(activity, registry);
        if (dev.isProbeWorkflow()) {
            worker.registerWorkflowImplementationTypes(L1ProbeWorkflowImpl.class);
            LOG.warn("L1 dev probe workflow is registered; do not enable l1.dev.probe-workflow outside local/qa");
        }
        if (dev.isReferenceWorkflow()) {
            factory.newWorker(dev.getReferenceTaskQueue())
                    .registerWorkflowImplementationTypes(ReferenceAxisPrecheckWorkflowImpl.class);
            LOG.warn("Dev reference L2 workflow (ReferenceAxisPrecheckWorkflow) polling task queue '{}'; "
                    + "do not enable l1.dev.reference-workflow outside local/qa", dev.getReferenceTaskQueue());
        }
        factory.start();
        running = true;
        LOG.info("L1 worker polling task queue '{}' on {} (namespace {})", settings.getTaskQueue(),
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
