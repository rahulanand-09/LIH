package com.rupeek.flow.task;

import com.mongodb.client.MongoClient;
import com.rupeek.flow.task.executor.gen.GenTaskExecutor;
import com.rupeek.flow.task.executor.http.HttpClientFactory;
import com.rupeek.flow.task.executor.http.HttpTaskExecutor;
import com.rupeek.flow.task.executor.map.MapTaskExecutor;
import com.rupeek.flow.task.executor.mongo.MongoDatabaseProvider;
import com.rupeek.flow.task.executor.mongo.MongoTaskExecutor;
import com.rupeek.flow.audit.AuditWriter;
import com.rupeek.flow.audit.PiiMasker;
import com.rupeek.flow.audit.TaskAuditor;
import com.rupeek.flow.expr.CelExpressionEngine;
import com.rupeek.flow.expr.ExprLimits;
import com.rupeek.flow.expr.ExpressionEngine;
import com.rupeek.flow.expr.TemplateEvaluator;
import com.rupeek.flow.expr.fn.RkFunctions;
import com.rupeek.flow.resource.AuthProviders;
import com.rupeek.flow.resource.ClasspathResourceProvider;
import com.rupeek.flow.resource.EnvSecretResolver;
import com.rupeek.flow.resource.ResourceConfigLoader;
import com.rupeek.flow.resource.ResourceProvider;
import com.rupeek.flow.resource.SecretResolver;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Builds a real, fully-wired {@link TaskActivityImpl} for tests — the same wiring
 * {@code FlowAutoConfiguration} does for production, assembled by hand here the same way these test classes
 * already hand-assemble {@code L1Components} for the (still present, not yet fully replaced) RECIPE bridge.
 * Used by every test that needs native HTTP/MONGO/MAP/GEN nodes to actually execute, against real WireMock and a
 * real Mongo (Testcontainers or the local {@code lih-mongo} container).
 */
public final class TestNativeExecutors {

    private TestNativeExecutors() {
    }

    public static TaskActivityImpl build(Map<String, String> env, MongoClient mongoClient, String mongoDatasourceName,
                                         AuditWriter auditWriter) {
        Executors e = buildExecutors(env, mongoClient, mongoDatasourceName, auditWriter);
        return new TaskActivityImpl(e.http(), e.mongo(), e.map(), e.gen());
    }

    /** The four native executors individually — e.g. for {@link com.rupeek.flow.registry.TaskPublisher}'s own tests. */
    public static Executors buildExecutors(Map<String, String> env, MongoClient mongoClient, String mongoDatasourceName,
                                           AuditWriter auditWriter) {
        ExpressionEngine expressions = new CelExpressionEngine(ExprLimits.DEFAULT, List.of(new RkFunctions()));
        TemplateEvaluator templates = new TemplateEvaluator(expressions);
        ResourceProvider resources = new ClasspathResourceProvider(
                ResourceConfigLoader.load("classpath:flow-config/resources"), "local", env::get);
        SecretResolver secrets = new EnvSecretResolver(env::get);
        AuthProviders auth = AuthProviders.defaults();
        HttpClientFactory httpClients = new HttpClientFactory();
        MongoDatabaseProvider mongoDatabases = new MongoDatabaseProvider(Map.of(mongoDatasourceName, mongoClient));
        PiiMasker piiMasker = new PiiMasker();
        TaskAuditor auditor = new TaskAuditor(auditWriter, piiMasker, Clock.systemUTC());

        HttpTaskExecutor http = new HttpTaskExecutor(templates, expressions, resources, httpClients, auth, secrets, auditor);
        MongoTaskExecutor mongo = new MongoTaskExecutor(resources, mongoDatabases, expressions, auditor);
        MapTaskExecutor map = new MapTaskExecutor(templates, resources);
        GenTaskExecutor gen = new GenTaskExecutor(templates);
        return new Executors(http, mongo, map, gen);
    }

    public record Executors(HttpTaskExecutor http, MongoTaskExecutor mongo, MapTaskExecutor map, GenTaskExecutor gen) {
    }
}
