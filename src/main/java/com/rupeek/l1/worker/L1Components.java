package com.rupeek.l1.worker;

import com.mongodb.client.MongoClient;
import com.rupeek.l1.engine.ConfigCompiler;
import com.rupeek.l1.engine.ErrorClassifier;
import com.rupeek.l1.engine.OperationInvoker;
import com.rupeek.l1.engine.OutputGuard;
import com.rupeek.l1.engine.RecipeRunner;
import com.rupeek.l1.engine.RecipeSource;
import com.rupeek.l1.expr.CelExpressionEngine;
import com.rupeek.l1.expr.ExprLimits;
import com.rupeek.l1.expr.ExpressionEngine;
import com.rupeek.l1.expr.TemplateEvaluator;
import com.rupeek.l1.expr.fn.RkFunctions;
import com.rupeek.l1.intercept.AuditInterceptor;
import com.rupeek.l1.intercept.AuditWriter;
import com.rupeek.l1.intercept.InterceptorChain;
import com.rupeek.l1.intercept.LoggingInterceptor;
import com.rupeek.l1.intercept.MetricsInterceptor;
import com.rupeek.l1.intercept.OperationInterceptor;
import com.rupeek.l1.intercept.PiiMasker;
import com.rupeek.l1.intercept.TracingInterceptor;
import com.rupeek.l1.model.Resource;
import com.rupeek.l1.ops.OperationRegistry;
import com.rupeek.l1.ops.gen.GenOperationExecutor;
import com.rupeek.l1.ops.http.AuthProviders;
import com.rupeek.l1.ops.http.HttpClientFactory;
import com.rupeek.l1.ops.http.HttpOperationExecutor;
import com.rupeek.l1.ops.map.MapOperationExecutor;
import com.rupeek.l1.ops.mongo.MongoDatabaseProvider;
import com.rupeek.l1.ops.mongo.MongoOperationExecutor;
import com.rupeek.l1.engine.ResourceProvider;
import com.rupeek.l1.registry.ClasspathConfigLoader;
import com.rupeek.l1.registry.ConfigStore;
import com.rupeek.l1.registry.InMemoryResourceProvider;
import com.rupeek.l1.registry.RegistryService;
import com.rupeek.l1.registry.StaticRecipeSource;
import com.rupeek.l1.registry.StoreRecipeSource;
import com.rupeek.l1.registry.StoreResourceProvider;
import com.rupeek.l1.secrets.SecretResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Wires the L1 worker from plain objects. The single wiring path used by both the Spring auto-configuration and
 * the tests, so what is tested is what runs.
 */
public record L1Components(
        ExpressionEngine expressions,
        ResourceProvider resources,
        RecipeSource recipes,
        RegistryService registry,
        RecipeRunner runner,
        TaskActivityImpl activity) {

    /**
     * @param envName        active environment ({@code local}, {@code qa}, {@code prod})
     * @param configLocation e.g. {@code classpath:l1-config}; used when {@code configStore} is null
     * @param configStore    published config (the registry); null to load {@code configLocation} instead
     * @param resourceTtl    how long a resource read from the registry is cached
     * @param mongoClients   datasourceRef to client (for MONGO operations)
     * @param auditWriter    null to disable audit writing
     * @param envLookup      how {@code baseUrlRef} values are resolved (environment variables in production)
     */
    public record Settings(
            String envName,
            String configLocation,
            ConfigStore configStore,
            Duration resourceTtl,
            Map<String, MongoClient> mongoClients,
            SecretResolver secrets,
            AuditWriter auditWriter,
            MeterRegistry meters,
            ObservationRegistry observations,
            Function<String, String> envLookup,
            Clock clock) {
    }

    public static L1Components create(Settings s) {
        ExpressionEngine expressions = standardExpressions();
        OperationRegistry operations = standardOperations(new TemplateEvaluator(expressions), s.mongoClients(),
                s.secrets());

        ResourceProvider resources;
        RecipeSource recipes;
        if (s.configStore() == null) {
            ClasspathConfigLoader.LoadedConfig config = ClasspathConfigLoader.load(s.configLocation());
            Map<String, Resource> resourceMap = config.resources().stream()
                    .collect(Collectors.toMap(Resource::resource, r -> r));
            resources = new InMemoryResourceProvider(resourceMap, s.envName(), s.envLookup());
            recipes = new StaticRecipeSource(config.recipes(), config.operations(),
                    new ConfigCompiler(expressions, operations, resources));
        } else {
            resources = new StoreResourceProvider(s.configStore(), s.envName(), s.envLookup(), s.resourceTtl());
            StoreRecipeSource source = new StoreRecipeSource(s.configStore(),
                    new ConfigCompiler(expressions, operations, resources));
            source.warmUp();
            recipes = source;
        }

        PiiMasker masker = new PiiMasker();
        List<OperationInterceptor> interceptors = new ArrayList<>();
        interceptors.add(new TracingInterceptor(s.observations()));
        interceptors.add(new LoggingInterceptor());
        interceptors.add(new MetricsInterceptor(s.meters()));
        if (s.auditWriter() != null) {
            interceptors.add(new AuditInterceptor(s.auditWriter(), masker, s.clock()));
        }
        OperationInvoker invoker = new OperationInvoker(operations, resources, new InterceptorChain(interceptors),
                expressions, new ErrorClassifier());
        RecipeRunner runner = new RecipeRunner(recipes, resources, invoker, expressions, new OutputGuard(), masker,
                s.meters(), s.clock());
        return new L1Components(expressions, resources, recipes, new RegistryService(recipes), runner,
                new TaskActivityImpl(runner));
    }

    /** The CEL engine with the {@code rk.*} library, as the worker runs it. */
    public static ExpressionEngine standardExpressions() {
        return new CelExpressionEngine(ExprLimits.DEFAULT, List.of(new RkFunctions()));
    }

    /** Every operation kind, as the worker runs them. Publish validation uses the same set. */
    public static OperationRegistry standardOperations(TemplateEvaluator templates,
                                                       Map<String, MongoClient> mongoClients, SecretResolver secrets) {
        return new OperationRegistry(List.of(
                new HttpOperationExecutor(templates, new HttpClientFactory(), AuthProviders.defaults(), secrets),
                new MongoOperationExecutor(new MongoDatabaseProvider(mongoClients)),
                new MapOperationExecutor(templates),
                new GenOperationExecutor()));
    }
}
