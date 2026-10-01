package com.rupeek.lih;

import com.rupeek.lih.l2.Adapters;
import com.rupeek.lih.l2.FlowWorkflowImpl;
import com.rupeek.lih.l2.L1Registry;
import com.rupeek.lih.l2.StepActivitiesImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TemporalConfig {

    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs serviceStubs(LihProperties props) {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(props.getTemporalTarget()).build());
    }

    @Bean
    WorkflowClient workflowClient(WorkflowServiceStubs stubs, LihProperties props) {
        return WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace(props.getNamespace()).build());
    }

    /** One task queue per lender ("lender-axis"), all served by this shared worker pool for now. */
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    WorkerFactory workerFactory(WorkflowClient client, LihProperties props, L1Registry l1,
                                AxisTransformers transformers, PrecheckSinks sinks) {
        WorkerFactory factory = WorkerFactory.newInstance(client);
        StepActivitiesImpl activities = new StepActivitiesImpl(l1.operations(), Adapters.defaults(),
                transformers.all(), sinks.all());
        for (String lender : props.getLenders()) {
            Worker worker = factory.newWorker(taskQueue(lender));
            worker.registerWorkflowImplementationTypes(FlowWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities);
        }
        return factory;
    }

    public static String taskQueue(String lenderSlug) { return "lender-" + lenderSlug; }
}
