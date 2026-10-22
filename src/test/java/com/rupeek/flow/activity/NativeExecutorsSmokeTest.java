package com.rupeek.flow.activity;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.rupeek.flow.activity.gen.GenTaskExecutor;
import com.rupeek.flow.activity.http.HttpClientFactory;
import com.rupeek.flow.activity.http.HttpTaskExecutor;
import com.rupeek.flow.activity.map.MapTaskExecutor;
import com.rupeek.flow.activity.mongo.MongoDatabaseProvider;
import com.rupeek.flow.activity.mongo.MongoTaskExecutor;
import com.rupeek.flow.audit.AuditWriter;
import com.rupeek.flow.audit.PiiMasker;
import com.rupeek.flow.audit.TaskAuditor;
import com.rupeek.flow.expr.CelExpressionEngine;
import com.rupeek.flow.expr.ExprLimits;
import com.rupeek.flow.expr.ExpressionEngine;
import com.rupeek.flow.expr.TemplateEvaluator;
import com.rupeek.flow.expr.fn.RkFunctions;
import com.rupeek.flow.model.OperationKind;
import com.rupeek.flow.resource.AuthProviders;
import com.rupeek.flow.resource.ClasspathResourceProvider;
import com.rupeek.flow.resource.EnvSecretResolver;
import com.rupeek.flow.resource.ResourceConfigLoader;
import com.rupeek.flow.resource.ResourceProvider;
import io.temporal.failure.ApplicationFailure;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the native {@code HTTP}/{@code MONGO}/{@code MAP}/{@code GEN} executors work end to end — real
 * WireMock, real Mongo (Testcontainers), real CEL — BEFORE any production flow JSON is ported off the RECIPE
 * bridge (the plan's verification step: "unit-test each native executor directly" first). Exercises
 * {@link TaskActivityImpl} directly, bypassing {@code FlowWorkflowImpl}/Temporal entirely — this is a plain
 * unit test of the dispatcher and the four executors, not a workflow test.
 */
class NativeExecutorsSmokeTest {

    private static MongoDBContainer mongoContainer;
    private static MongoClient mongoClient;

    private WireMockServer http;
    private MongoDatabase db;
    private TaskActivityImpl taskActivity;

    @BeforeEach
    void setUp() {
        http = new WireMockServer(options().dynamicPort());
        http.start();
        http.stubFor(get(urlPathEqualTo("/customers/42"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\": \"42\", \"name\": \"Ada Lovelace\"}")));
        http.stubFor(get(urlPathEqualTo("/customers/missing"))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"not found\"}")));

        if (mongoClient == null) {
            mongoContainer = new MongoDBContainer("mongo:7.0");
            mongoContainer.start();
            mongoClient = MongoClients.create(mongoContainer.getConnectionString());
        }
        db = mongoClient.getDatabase("flow_native_test");
        db.getCollection("SmokeTestDocs").drop();

        ExpressionEngine expressions = new CelExpressionEngine(ExprLimits.DEFAULT, List.of(new RkFunctions()));
        TemplateEvaluator templates = new TemplateEvaluator(expressions);
        Map<String, com.rupeek.flow.resource.Resource> resourceDocs = ResourceConfigLoader.load("classpath:flow-config/resources");
        // Add one test-only HTTP resource pointing at WireMock, not loaded from flow-config/resources.
        resourceDocs = new java.util.LinkedHashMap<>(resourceDocs);
        resourceDocs.put("test-http", new com.rupeek.flow.resource.Resource("test-http",
                com.rupeek.flow.resource.Resource.ResourceKind.HTTP, null, Map.of(), null, null,
                Map.of("local", new com.rupeek.flow.resource.Resource.ResourceEnv(http.baseUrl(), null, Map.of())),
                null, null, List.of()));
        resourceDocs.put("test-mongo", new com.rupeek.flow.resource.Resource("test-mongo",
                com.rupeek.flow.resource.Resource.ResourceKind.MONGO, null, Map.of(), null, null, Map.of(),
                "default", "flow_native_test", List.of("SmokeTestDocs")));
        ResourceProvider resources = new ClasspathResourceProvider(resourceDocs, "local");

        HttpTaskExecutor httpExecutor = new HttpTaskExecutor(templates, expressions, resources, new HttpClientFactory(),
                AuthProviders.defaults(), new EnvSecretResolver(name -> null), noopAuditor());
        MongoTaskExecutor mongoExecutor = new MongoTaskExecutor(resources, new MongoDatabaseProvider(Map.of("default", mongoClient)),
                expressions, noopAuditor());
        MapTaskExecutor mapExecutor = new MapTaskExecutor(templates, resources);
        GenTaskExecutor genExecutor = new GenTaskExecutor(templates);
        taskActivity = new TaskActivityImpl(httpExecutor, mongoExecutor, mapExecutor, genExecutor);
    }

    @AfterEach
    void tearDown() {
        http.stop();
    }

    private static TaskAuditor noopAuditor() {
        AuditWriter noop = record -> { };
        return new TaskAuditor(noop, new PiiMasker(), Clock.systemUTC());
    }

    @Test
    void genStableIdIsDeterministicPerSeed() {
        TaskResult r1 = taskActivity.execute(request(OperationKind.GEN,
                Map.of("action", "STABLE_ID", "params", Map.of("reuse", "", "seed", "=inputs.seed")),
                Map.of("seed", "loan-1:r1")));
        TaskResult r2 = taskActivity.execute(request(OperationKind.GEN,
                Map.of("action", "STABLE_ID", "params", Map.of("reuse", "", "seed", "=inputs.seed")),
                Map.of("seed", "loan-1:r1")));
        assertThat(r1.outcome()).isEqualTo("OK");
        assertThat(r1.outputs().get("value")).isEqualTo(r2.outputs().get("value"));
    }

    @Test
    void mapBuildsOutputsFromInputs() {
        TaskResult result = taskActivity.execute(request(OperationKind.MAP,
                Map.of("template", Map.of("value", "=inputs.a + '-' + inputs.b")),
                Map.of("a", "x", "b", "y")));
        assertThat(result.outcome()).isEqualTo("OK");
        assertThat(result.outputs()).containsEntry("value", "x-y");
    }

    @Test
    void mapCanActAsAPureGuardViaTheReservedOutcomeKey() {
        TaskResult yes = taskActivity.execute(request(OperationKind.MAP,
                Map.of("template", Map.of("outcome", "=inputs.flag ? 'YES' : 'NO'")),
                Map.of("flag", true)));
        TaskResult no = taskActivity.execute(request(OperationKind.MAP,
                Map.of("template", Map.of("outcome", "=inputs.flag ? 'YES' : 'NO'")),
                Map.of("flag", false)));
        assertThat(yes.outcome()).isEqualTo("YES");
        assertThat(yes.outputs()).isEmpty();
        assertThat(no.outcome()).isEqualTo("NO");
    }

    @Test
    void mongoInsertThenFindOneRoundTrips() {
        TaskResult inserted = taskActivity.execute(request(OperationKind.MONGO,
                Map.of("resource", "test-mongo", "collection", "SmokeTestDocs", "action", "INSERT",
                        "set", Map.of("loanRequestId", ":loanRequestId", "status", "NEW"),
                        "out", Map.of("inserted", "result.inserted")),
                Map.of("loanRequestId", "LR-1")));
        assertThat(inserted.outcome()).isEqualTo("OK");
        assertThat(inserted.outputs().get("inserted")).isEqualTo(1L);

        TaskResult found = taskActivity.execute(request(OperationKind.MONGO,
                Map.of("resource", "test-mongo", "collection", "SmokeTestDocs", "action", "FIND_ONE",
                        "filter", Map.of("loanRequestId", ":loanRequestId"),
                        "out", Map.of("found", "result != null", "status", "result != null ? result.status : null")),
                Map.of("loanRequestId", "LR-1")));
        assertThat(found.outputs()).containsEntry("found", true).containsEntry("status", "NEW");
    }

    @Test
    void httpSuccessExtractsDeclaredOutputs() {
        TaskResult result = taskActivity.execute(request(OperationKind.HTTP,
                Map.of("resource", "test-http", "method", "GET", "path", "/customers/42",
                        "success", "resp.status == 200",
                        "out", Map.of("name", "resp.body.name")),
                Map.of()));
        assertThat(result.outcome()).isEqualTo("OK");
        assertThat(result.outputs()).containsEntry("name", "Ada Lovelace");
    }

    @Test
    void httpBusinessFailureWithOnErrorMappingReturnsAnOutcomeInsteadOfThrowing() {
        TaskResult result = taskActivity.execute(request(OperationKind.HTTP,
                Map.of("resource", "test-http", "method", "GET", "path", "/customers/missing",
                        "success", "resp.status == 200",
                        "onError", Map.of("BUSINESS", "NOT_FOUND")),
                Map.of()));
        assertThat(result.outcome()).isEqualTo("NOT_FOUND");
        assertThat(result.outputs()).isEmpty();
    }

    @Test
    void httpFailureWithNoOnErrorMappingThrowsAClassifiedApplicationFailure() {
        assertThatThrownBy(() -> taskActivity.execute(request(OperationKind.HTTP,
                Map.of("resource", "test-http", "method", "GET", "path", "/customers/missing",
                        "success", "resp.status == 200"),
                Map.of())))
                .isInstanceOf(ApplicationFailure.class)
                .satisfies(e -> assertThat(((ApplicationFailure) e).getType()).isEqualTo(TaskFailureType.BUSINESS.name()));
    }

    private static TaskRequest request(OperationKind kind, Map<String, Object> fields, Map<String, Object> inputs) {
        return new TaskRequest(kind, null, 0, "case-" + Instant.now().toEpochMilli(), "node", 1, inputs, fields);
    }
}
