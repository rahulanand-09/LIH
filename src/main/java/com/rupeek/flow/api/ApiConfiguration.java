package com.rupeek.flow.api;

import com.mongodb.client.MongoClient;
import com.rupeek.flow.config.FlowAutoConfiguration;
import com.rupeek.flow.config.FlowProperties;
import com.rupeek.flow.config.TemporalConnection;
import com.rupeek.flow.registry.FlowPublisher;
import com.rupeek.flow.registry.FlowSource;
import com.rupeek.flow.registry.TaskPublisher;
import com.rupeek.flow.registry.store.ConfigStore;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The HTTP control surface (flows/cases/admin/callback), active with the engine ({@code flow.enabled=true}) in a
 * servlet app against the Mongo registry. Auth is a plain filter (no Spring Security); startup fails fast without
 * {@code flow.api.key} unless {@code flow.api.auth.enabled=false}.
 */
@AutoConfiguration(after = {FlowAutoConfiguration.class,
        com.rupeek.flow.config.FlowRegistryAutoConfiguration.class})
@ConditionalOnProperty(prefix = "flow", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "flow.registry", name = "mode", havingValue = "mongo")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(ApiProperties.class)
@Import({CaseController.class, AdminController.class, CallbackController.class, ApiExceptionHandler.class})
public class ApiConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(ApiConfiguration.class);

    @Bean
    public ApiAuthFilter flowApiAuthFilter(ApiProperties props) {
        boolean enabled = props.getAuth().isEnabled();
        if (enabled && props.getKey().isBlank()) {
            throw new IllegalStateException(
                    "flow.api.key (env FLOW_API_KEY) must be set, or flow.api.auth.enabled=false for local dev");
        }
        if (!enabled) {
            LOG.warn("flow.api.auth.enabled=false: /flows, /cases, /admin are UNAUTHENTICATED");
        }
        if (props.getCallbackSecret().isBlank()) {
            LOG.warn("flow.api.callback-secret (env FLOW_CALLBACK_SECRET) is blank: /v1/callback auth is DISABLED");
        }
        return new ApiAuthFilter(props.getKey(), props.getCallbackSecret(), enabled);
    }

    @Bean(destroyMethod = "shutdown")
    public io.temporal.serviceclient.WorkflowServiceStubs flowApiTemporalStubs(FlowProperties props) {
        return TemporalConnection.stubs(props.getTemporal());
    }

    @Bean
    public WorkflowClient flowApiWorkflowClient(io.temporal.serviceclient.WorkflowServiceStubs flowApiTemporalStubs,
                                                FlowProperties props) {
        return WorkflowClient.newInstance(flowApiTemporalStubs,
                WorkflowClientOptions.newBuilder().setNamespace(props.getTemporal().getNamespace()).build());
    }

    @Bean
    public CaseService flowCaseService(WorkflowClient flowApiWorkflowClient, FlowSource flowSource, FlowProperties props) {
        return new CaseService(flowApiWorkflowClient, flowSource, props.getTemporal().getTaskQueue());
    }

    @Bean
    @ConditionalOnProperty(prefix = "flow.registry.seed", name = "enabled", havingValue = "true")
    public ApplicationRunner flowRegistrySeedRunner(ConfigStore flowConfigStore, FlowPublisher flowPublisher,
                                                    TaskPublisher taskPublisher) {
        RegistrySeeder seeder = new RegistrySeeder(flowConfigStore, flowPublisher, taskPublisher);
        return args -> seeder.seed();
    }
}
