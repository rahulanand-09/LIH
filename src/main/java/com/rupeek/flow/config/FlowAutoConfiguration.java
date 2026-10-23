package com.rupeek.flow.config;

import com.rupeek.flow.worker.FlowTemporalWorker;

import com.mongodb.client.MongoClient;
import com.rupeek.flow.task.TaskActivityImpl;
import com.rupeek.flow.task.executor.gen.GenTaskExecutor;
import com.rupeek.flow.task.executor.http.HttpClientFactory;
import com.rupeek.flow.task.executor.http.HttpTaskExecutor;
import com.rupeek.flow.task.executor.map.MapTaskExecutor;
import com.rupeek.flow.task.executor.mongo.MongoDatabaseProvider;
import com.rupeek.flow.task.executor.mongo.MongoTaskExecutor;
import com.rupeek.flow.audit.AuditWriter;
import com.rupeek.flow.audit.MongoAuditWriter;
import com.rupeek.flow.audit.PiiMasker;
import com.rupeek.flow.audit.TaskAuditor;
import com.rupeek.flow.expr.CelExpressionEngine;
import com.rupeek.flow.expr.ExprLimits;
import com.rupeek.flow.expr.ExpressionEngine;
import com.rupeek.flow.expr.TemplateEvaluator;
import com.rupeek.flow.expr.fn.RkFunctions;
import com.rupeek.flow.registry.activity.FetchFlowActivityImpl;
import com.rupeek.flow.registry.activity.FetchTaskActivityImpl;
import com.rupeek.flow.registry.FlowPublisher;
import com.rupeek.flow.registry.FlowRegistryService;
import com.rupeek.flow.registry.FlowSource;
import com.rupeek.flow.registry.activity.ResolveFlowActivityImpl;
import com.rupeek.flow.registry.activity.ResolveTaskActivityImpl;
import com.rupeek.flow.registry.StoreFlowSource;
import com.rupeek.flow.registry.StoreTaskSource;
import com.rupeek.flow.registry.TaskPublisher;
import com.rupeek.flow.registry.TaskRegistryService;
import com.rupeek.flow.registry.TaskSource;
import com.rupeek.flow.registry.store.ConfigStore;
import com.rupeek.flow.resource.AuthProviders;
import com.rupeek.flow.resource.ClasspathResourceProvider;
import com.rupeek.flow.resource.EnvSecretResolver;
import com.rupeek.flow.resource.ResourceConfigLoader;
import com.rupeek.flow.resource.ResourceProvider;
import com.rupeek.flow.resource.SecretResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Spring wiring for the flow engine, registered as an auto-configuration (no component scan of
 * {@code com.rupeek.flow} needed). Active only with {@code flow.enabled=true}. Flow owns its own Mongo-backed
 * registry ({@code flow.registry.mode=mongo}, its own {@code lih_flow_config} database) — it is not a collection
 * shared with {@code com.rupeek.l1}'s registry, so this configuration has no dependency on
 * {@code L1AutoConfiguration} at all.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"})
@ConditionalOnProperty(prefix = "flow", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(FlowProperties.class)
public class FlowAutoConfiguration {

    /**
     * The registry's own Mongo/Spring-Data wiring ({@code flow.registry.mode=mongo}) lives in the separate,
     * top-level {@link FlowRegistryAutoConfiguration} — see that class's javadoc for why it can't be a nested
     * {@code @Configuration} class here.
     */

    /** Holds the Mongo client the registry runs against and closes the client it opened (if any). */
    public record RegistryConnection(MongoClient ownClient, MongoClient effectiveClient, String databaseName) implements AutoCloseable {
        @Override
        public void close() {
            if (ownClient != null) {
                ownClient.close();
            }
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "flow.registry", name = "mode", havingValue = "mongo")
    public FlowSource flowSource(ConfigStore flowConfigStore) {
        return new StoreFlowSource(flowConfigStore);
    }

    @Bean
    public FlowRegistryService flowRegistryService(FlowSource flowSource) {
        return new FlowRegistryService(flowSource);
    }

    @Bean
    public ResolveFlowActivityImpl resolveFlowActivity(FlowRegistryService flowRegistryService) {
        return new ResolveFlowActivityImpl(flowRegistryService);
    }

    @Bean
    public FetchFlowActivityImpl fetchFlowActivity(FlowSource flowSource) {
        return new FetchFlowActivityImpl(flowSource);
    }

    /** Publish path for flow versions, the flow-side counterpart of L1's own CLI publish pipeline. */
    @Bean
    public FlowPublisher flowPublisher(ConfigStore flowConfigStore) {
        return new FlowPublisher(flowConfigStore, Clock.systemUTC());
    }

    // --- Task registry: a TaskNode's real fields, fetched by taskRef, never inlined (see docs/flow-lld.md §16) ---

    @Bean
    @ConditionalOnProperty(prefix = "flow.registry", name = "mode", havingValue = "mongo")
    public TaskSource taskSource(ConfigStore flowConfigStore) {
        return new StoreTaskSource(flowConfigStore);
    }

    @Bean
    public TaskRegistryService taskRegistryService(TaskSource taskSource) {
        return new TaskRegistryService(taskSource);
    }

    @Bean
    public ResolveTaskActivityImpl resolveTaskActivity(TaskRegistryService taskRegistryService) {
        return new ResolveTaskActivityImpl(taskRegistryService);
    }

    @Bean
    public FetchTaskActivityImpl fetchTaskActivity(TaskSource taskSource) {
        return new FetchTaskActivityImpl(taskSource);
    }

    @Bean
    public TaskPublisher taskPublisher(ConfigStore flowConfigStore, HttpTaskExecutor httpTaskExecutor,
                                       MongoTaskExecutor mongoTaskExecutor, MapTaskExecutor mapTaskExecutor,
                                       GenTaskExecutor genTaskExecutor) {
        return new TaskPublisher(flowConfigStore, Clock.systemUTC(), httpTaskExecutor, mongoTaskExecutor,
                mapTaskExecutor, genTaskExecutor);
    }

    // --- Native HTTP/MONGO/MAP/GEN executors (see docs/flow-lld.md's native-executors update) ---

    @Bean
    public ExpressionEngine flowExpressionEngine() {
        return new CelExpressionEngine(ExprLimits.DEFAULT, List.of(new RkFunctions()));
    }

    @Bean
    public TemplateEvaluator flowTemplateEvaluator(ExpressionEngine flowExpressionEngine) {
        return new TemplateEvaluator(flowExpressionEngine);
    }

    @Bean
    public ResourceProvider flowResourceProvider(FlowProperties props) {
        return new ClasspathResourceProvider(ResourceConfigLoader.load(props.getResourceLocation()), props.getEnv());
    }

    @Bean
    @ConditionalOnMissingBean
    public SecretResolver flowSecretResolver() {
        return new EnvSecretResolver();
    }

    @Bean
    public AuthProviders flowAuthProviders() {
        return AuthProviders.defaults();
    }

    @Bean
    public HttpClientFactory flowHttpClientFactory() {
        return new HttpClientFactory();
    }

    @Bean
    public MongoDatabaseProvider flowMongoDatabaseProvider(FlowProperties props, MongoClient appClient) {
        return new MongoDatabaseProvider(Map.of(props.getMongoDatasource(), appClient));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "flow.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MongoAuditWriter flowMongoAuditWriter(FlowProperties props, MongoClient appClient) {
        return new MongoAuditWriter(appClient.getDatabase(props.getAudit().getDatabase()), props.getAudit().getQueueCapacity());
    }

    @Bean
    public PiiMasker flowPiiMasker() {
        return new PiiMasker();
    }

    @Bean
    public TaskAuditor flowTaskAuditor(ObjectProvider<AuditWriter> writer, PiiMasker flowPiiMasker) {
        return new TaskAuditor(writer.getIfAvailable(() -> record -> { }), flowPiiMasker, Clock.systemUTC());
    }

    @Bean
    public HttpTaskExecutor httpTaskExecutor(TemplateEvaluator flowTemplateEvaluator, ExpressionEngine flowExpressionEngine,
                                             ResourceProvider flowResourceProvider, HttpClientFactory flowHttpClientFactory,
                                             AuthProviders flowAuthProviders, SecretResolver flowSecretResolver,
                                             TaskAuditor flowTaskAuditor) {
        return new HttpTaskExecutor(flowTemplateEvaluator, flowExpressionEngine, flowResourceProvider,
                flowHttpClientFactory, flowAuthProviders, flowSecretResolver, flowTaskAuditor);
    }

    @Bean
    public MongoTaskExecutor mongoTaskExecutor(ResourceProvider flowResourceProvider, MongoDatabaseProvider flowMongoDatabaseProvider,
                                               ExpressionEngine flowExpressionEngine, TaskAuditor flowTaskAuditor) {
        return new MongoTaskExecutor(flowResourceProvider, flowMongoDatabaseProvider, flowExpressionEngine, flowTaskAuditor);
    }

    @Bean
    public MapTaskExecutor mapTaskExecutor(TemplateEvaluator flowTemplateEvaluator, ResourceProvider flowResourceProvider) {
        return new MapTaskExecutor(flowTemplateEvaluator, flowResourceProvider);
    }

    @Bean
    public GenTaskExecutor genTaskExecutor(TemplateEvaluator flowTemplateEvaluator) {
        return new GenTaskExecutor(flowTemplateEvaluator);
    }

    @Bean
    public TaskActivityImpl taskActivity(HttpTaskExecutor httpTaskExecutor, MongoTaskExecutor mongoTaskExecutor,
                                         MapTaskExecutor mapTaskExecutor, GenTaskExecutor genTaskExecutor) {
        return new TaskActivityImpl(httpTaskExecutor, mongoTaskExecutor, mapTaskExecutor, genTaskExecutor);
    }

    @Bean
    public FlowTemporalWorker flowTemporalWorker(FlowProperties props, ResolveFlowActivityImpl resolveFlowActivity,
                                                 FetchFlowActivityImpl fetchFlowActivity, TaskActivityImpl taskActivity,
                                                 ResolveTaskActivityImpl resolveTaskActivity, FetchTaskActivityImpl fetchTaskActivity) {
        return new FlowTemporalWorker(props.getTemporal(), resolveFlowActivity, fetchFlowActivity, taskActivity,
                resolveTaskActivity, fetchTaskActivity);
    }
}
