package com.rupeek.l1.axis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.rupeek.l1.api.TaskRequest;
import com.rupeek.l1.api.TaskResult;
import com.rupeek.l1.engine.ConfigCompiler;
import com.rupeek.l1.engine.ErrorClassifier;
import com.rupeek.l1.engine.OperationInvoker;
import com.rupeek.l1.engine.OutputGuard;
import com.rupeek.l1.engine.RecipeRunner;
import com.rupeek.l1.expr.ExpressionEngine;
import com.rupeek.l1.expr.TemplateEvaluator;
import com.rupeek.l1.intercept.InterceptorChain;
import com.rupeek.l1.intercept.PiiMasker;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.OperationKind;
import com.rupeek.l1.model.Resource;
import com.rupeek.l1.ops.ExecContext;
import com.rupeek.l1.ops.OperationExecutor;
import com.rupeek.l1.ops.OperationRegistry;
import com.rupeek.l1.ops.PreparedOperation;
import com.rupeek.l1.ops.TransportException;
import com.rupeek.l1.ops.mongo.MongoTestSupport;
import com.rupeek.l1.registry.ClasspathConfigLoader;
import com.rupeek.l1.registry.InMemoryResourceProvider;
import com.rupeek.l1.registry.StaticRecipeSource;
import com.rupeek.l1.secrets.EnvSecretResolver;
import com.rupeek.l1.worker.L1Components;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rerun safety without a step journal (LLD section 9, decision P12). The worker "dies" right after each
 * operation's side effect; Temporal then runs the same {@code RunTask} again. Whatever step it died after:
 * one precheck document, one {@code client_loan_id} for every Axis call, the right final outcome, and Axis called
 * a second time only when the crash came after the Axis call and before its result was saved.
 */
class AxisDedupeRerunTest {

    private static final String AXIS_PATH = "/investor_integration/actions/invoke_api";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static WireMockServer server;

    private MongoCollection<Document> prechecks;
    private RecipeRunner runner;
    private final CrashSwitch crash = new CrashSwitch();

    /** Kills the "worker" once, right after the named operation's real execution. */
    static final class CrashSwitch {
        String afterOp;
        boolean fired;
    }

    static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash(String op) {
            super("worker died after " + op);
        }
    }

    /** Runs the real executor, then crashes if armed for this operation. */
    private record CrashAfter(OperationExecutor real, CrashSwitch crash) implements OperationExecutor {
        @Override
        public OperationKind kind() {
            return real.kind();
        }

        @Override
        public PreparedOperation prepare(OperationDef def) {
            return real.prepare(def);
        }

        @Override
        public Map<String, Object> execute(PreparedOperation op, Map<String, Object> params, ExecContext ctx)
                throws TransportException {
            Map<String, Object> out = real.execute(op, params, ctx);
            if (!crash.fired && op.def().op().equals(crash.afterOp)) {
                crash.fired = true;
                throw new SimulatedCrash(op.def().op());
            }
            return out;
        }
    }

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void setUp() throws IOException {
        server.resetAll();
        server.stubFor(get(urlPathEqualTo("/api/support/fetchloandetailsv2"))
                .willReturn(json(fixture("core-customer-response.json"))));
        server.stubFor(post(urlPathEqualTo(AXIS_PATH)).willReturn(json(fixture("axis-dedupe-success.json"))));

        var db = MongoTestSupport.client().getDatabase("lendercore");
        prechecks = db.getCollection("LenderCustomerPrecheck");
        prechecks.drop();

        Map<String, String> env = Map.of("AXIS_PRECHECK_BASEURL", server.baseUrl(), "CORE_BASEURL", server.baseUrl(),
                "AXIS_PRECHECK_API_KEY", "k", "CORE_TOKEN", "t");
        ExpressionEngine expressions = L1Components.standardExpressions();
        OperationRegistry real = L1Components.standardOperations(new TemplateEvaluator(expressions),
                Map.of("lendercore", MongoTestSupport.client()), new EnvSecretResolver(env::get));
        List<OperationExecutor> wrapped = new ArrayList<>();
        for (OperationKind kind : OperationKind.values()) {
            wrapped.add(new CrashAfter(real.executor(kind), crash));
        }
        OperationRegistry operations = new OperationRegistry(wrapped);
        var config = ClasspathConfigLoader.load("classpath:l1-config");
        var resources = new InMemoryResourceProvider(config.resources().stream()
                .collect(Collectors.toMap(Resource::resource, r -> r)), "local", env::get);
        var recipes = new StaticRecipeSource(config.recipes(), config.operations(),
                new ConfigCompiler(expressions, operations, resources));
        var invoker = new OperationInvoker(operations, resources, new InterceptorChain(List.of()), expressions,
                new ErrorClassifier());
        runner = new RecipeRunner(recipes, resources, invoker, expressions, new OutputGuard(), new PiiMasker(),
                new SimpleMeterRegistry(), Clock.systemUTC());
    }

    @ParameterizedTest(name = "crash after {0}: rerun gives {1}, Axis called {2}x")
    @CsvSource({
            "core.fetchCustomerByLoanRequest, INITIATED,         1",
            "precheck.findByLoanRequest,      INITIATED,         1",
            "gen.stableId,                    INITIATED,         1",
            "precheck.upsertInitiated,        INITIATED,         1",
            "map.axis.precheckRequest,        INITIATED,         1",
            "axis.precheck.invoke,            INITIATED,         2",
            "precheck.markDedupeDone,         ALREADY_INITIATED, 1",
    })
    void aRerunAfterACrashHasNoDuplicateSideEffects(String afterOp, String outcome, int axisCalls) throws Exception {
        String lr = "LR-RERUN-" + afterOp;
        TaskRequest request = new TaskRequest("axis.precheck.dedupe", 1, "case-" + lr, 1,
                Map.of("loanRequestId", lr, "runNo", 1));
        crash.afterOp = afterOp;

        assertThatThrownBy(() -> runner.run(request, RecipeRunner.Heartbeat.NONE)).isInstanceOf(SimulatedCrash.class);
        TaskResult rerun = runner.run(request, RecipeRunner.Heartbeat.NONE);

        assertThat(rerun.outcome()).isEqualTo(outcome);
        List<Document> docs = prechecks.find(Filters.eq("loanRequestId", lr)).into(new ArrayList<>());
        assertThat(docs).as("one precheck document").hasSize(1);
        Document doc = docs.get(0);
        assertThat(doc.getString("applicantId")).isEqualTo("APP-TEST-9");

        List<String> sentIds = new ArrayList<>();
        for (LoggedRequest r : server.findAll(postRequestedFor(urlPathEqualTo(AXIS_PATH)))) {
            sentIds.add(JSON.readTree(r.getBodyAsString()).get("client_loan_id").asText());
        }
        assertThat(sentIds).as("Axis calls").hasSize(axisCalls);
        assertThat(sentIds).as("one client_loan_id everywhere").containsOnly(doc.getString("client_loan_id"));
        assertThat(rerun.outputs().get("clientLoanId")).isEqualTo(doc.getString("client_loan_id"));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = AxisDedupeRerunTest.class.getClassLoader()
                .getResourceAsStream("l1-fixtures/axis/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
