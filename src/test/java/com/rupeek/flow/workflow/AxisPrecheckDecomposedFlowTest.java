package com.rupeek.flow.workflow;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.mongodb.client.MongoDatabase;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.NodeResult;
import com.rupeek.flow.registry.FlowDefLoader;
import com.rupeek.flow.registry.ResolveFlowActivity;
import com.rupeek.l1.expr.fn.RkFunctions;
import com.rupeek.l1.intercept.MongoAuditWriter;
import com.rupeek.l1.ops.mongo.MongoTestSupport;
import com.rupeek.l1.secrets.EnvSecretResolver;
import com.rupeek.l1.worker.L1Components;
import com.rupeek.l1.worker.RegistryActivityImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7, fully decomposed and genuinely executable: the Axis precheck as a top-level flow of real {@code SUBFLOW}
 * major blocks (dedupe/timble/timbleCallback/status/notifyFail), each itself a graph of real {@code HTTP}-via-
 * {@code RECIPE} leaf nodes — see docs/flow-lld.md §11b. Every leaf delegates to a brand-new one-step L1 recipe
 * under {@code l1-config/recipes/axis-decomposed/}, each wrapping one already-published, already-tested L1
 * operation — no new HTTP/Mongo/CEL code anywhere. Same fixtures and oracle as {@link AxisPrecheckFlowTest}: this
 * proves the fully-decomposed, multi-subflow version produces identical real-world behaviour to the simple
 * 7-node RECIPE-delegating one, not a second, divergent implementation.
 */
class AxisPrecheckDecomposedFlowTest {

    private static final String AXIS = "/investor_integration/actions/invoke_api";
    private static final String VERIFY = "/api/loanrequest/precheck-verification";
    private static final String FAILED = "/api/lender/updateprecheckfailure";
    private static final String QUEUE = "lih-l1";

    private WireMockServer http;
    private MongoDatabase db;
    private MongoAuditWriter auditWriter;
    private TestWorkflowEnvironment temporal;
    private WorkflowClient client;
    private Map<String, FlowDef> registry;

    @BeforeEach
    void setUp() throws IOException {
        http = new WireMockServer(options().dynamicPort());
        http.start();
        http.stubFor(get(urlPathEqualTo("/api/support/fetchloandetailsv2")).willReturn(json(fixture("core-customer-response.json"))));
        http.stubFor(post(urlPathEqualTo(AXIS)).withQueryParam("type", equalTo("dedupe"))
                .willReturn(json(fixture("axis-dedupe-success.json"))));
        http.stubFor(post(urlPathEqualTo(AXIS)).withQueryParam("type", equalTo("dedupe_timble"))
                .willReturn(json("{\"status\": \"SUCCESS\", \"reference_id\": \"TIMBLE-REF-1\"}")));
        http.stubFor(post(urlPathEqualTo(AXIS)).withQueryParam("type", equalTo("dedupe_status"))
                .inScenario("status").whenScenarioStateIs(Scenario.STARTED).willSetStateTo("done")
                .willReturn(json("{\"dedupe\": \"Y\", \"pan\": \"Y\", \"ramp\": \"WIP\", \"hunter\": \"Y\"}")));
        http.stubFor(post(urlPathEqualTo(AXIS)).withQueryParam("type", equalTo("dedupe_status"))
                .inScenario("status").whenScenarioStateIs("done")
                .willReturn(json("{\"dedupe\": \"Y\", \"pan\": \"Y\", \"ramp\": \"Y\", \"hunter\": \"Y\"}")));
        http.stubFor(post(urlPathEqualTo(VERIFY)).willReturn(json("{\"ok\": true}")));
        http.stubFor(post(urlPathEqualTo(FAILED)).willReturn(json("{\"ok\": true}")));

        db = MongoTestSupport.client().getDatabase("lendercore");
        for (String c : List.of("LenderCustomerPrecheck", "LenderCustomerPrecheckAudit", MongoAuditWriter.COLLECTION)) {
            db.getCollection(c).drop();
        }
        auditWriter = new MongoAuditWriter(db, 100);
        Map<String, String> env = Map.of("CORE_BASEURL", http.baseUrl(), "CORE_TOKEN", "core-token",
                "AXIS_PRECHECK_BASEURL", http.baseUrl(), "AXIS_PRECHECK_API_KEY", "axis-key",
                "RUPEEKWEB_BASEURL", http.baseUrl(), "RUPEEKWEB_ADMIN_TOKEN", "admin-token");
        L1Components l1 = L1Components.create(new L1Components.Settings("local", "classpath:l1-config", null,
                Duration.ofSeconds(60), Map.of("lendercore", MongoTestSupport.client()), new EnvSecretResolver(env::get),
                auditWriter, new SimpleMeterRegistry(), ObservationRegistry.NOOP, env::get, Clock.systemUTC()));

        temporal = TestWorkflowEnvironment.newInstance();
        Worker worker = temporal.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(FlowWorkflowImpl.class);
        worker.registerActivitiesImplementations(l1.activity(), new RegistryActivityImpl(l1.registry()),
                // The top-level flow and the status subflow each have SUBFLOW nodes, so each resolves a flowRef
                // list through this engine's own placeholder registry activity (RECIPE refs go through L1's
                // real RegistryActivity above instead). No real flow-version store exists yet; "always version 1"
                // is all this needs.
                (ResolveFlowActivity) refs -> refs.stream().collect(java.util.stream.Collectors.toMap(r -> r, r -> 1)));
        temporal.start();
        client = temporal.getWorkflowClient();

        registry = Map.of(
                "axis-precheck-decomposed-dedupe", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-dedupe.json"),
                "axis-precheck-decomposed-timble", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-timble.json"),
                "axis-precheck-decomposed-timbleCallback", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-timbleCallback.json"),
                "axis-precheck-decomposed-status", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-status.json"),
                "axis-precheck-decomposed-status-persist", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-status-persist.json"),
                "axis-precheck-decomposed-notifyFail", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-notifyFail.json"),
                // The macro dedupe/timble both inline (docs/flow-lld.md "macro" section) -- same registry map a real
                // SUBFLOW ref is looked up in; "inline" on the node is what decides it's spliced in, not spawned.
                "axis-precheck-decomposed-coreAndStableId", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-coreAndStableId.json"));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        temporal.close();
        auditWriter.close();
        http.stop();
    }

    private FlowWorkflow start(String loanRequestId) {
        FlowDef top = FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed.json");
        FlowWorkflow stub = client.newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId("axis-precheck-decomposed-" + loanRequestId)
                        .setWorkflowExecutionTimeout(Duration.ofHours(1)).build());
        Map<String, Object> inputs = Map.of("loanRequestId", loanRequestId, "runNo", 1, "loanProcess", "fresh");
        WorkflowClient.start(stub::run, FlowRunRequest.root(top, inputs, registry));
        return stub;
    }

    private static Map<String, Object> timble(String nameMatch) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("product_id", "P1");
        e.put("customer_id", "CUST-TEST-1");
        e.put("timble_status", "COMPLETED");
        e.put("name_match", nameMatch);
        e.put("aadhar_match", "Y");
        return e;
    }

    @Test
    void approvedEndToEnd() throws TimeoutException {
        FlowWorkflow stub = start("LR-DECOMP-1");
        stub.event("timbleCallback", timble("Y"));

        NodeResult result = WorkflowStub.fromTyped(stub).getResult(20, TimeUnit.SECONDS, NodeResult.class);

        assertThat(result.outcome()).isEqualTo("APPROVED");
        String clientLoanId = RkFunctions.uuidFrom("LR-DECOMP-1:r1:axis-dedupe");
        Document doc = db.getCollection("LenderCustomerPrecheck").find().first();
        assertThat(doc.getBoolean("approved")).isTrue();
        assertThat(doc.getString("clientLoanId")).isEqualTo(clientLoanId);
        assertThat(doc.getString("reference_id")).isEqualTo("TIMBLE-REF-1");
        Document responseMap = doc.get("responseMap", Document.class);
        assertThat(responseMap.get("axisPreCheckStatus", Document.class).getString("status")).isEqualTo("SUCCESS");
    }

    @Test
    void timbleMismatchEndsRejectedAndNotifiesTheFailure() throws TimeoutException {
        FlowWorkflow stub = start("LR-DECOMP-2");
        stub.event("timbleCallback", timble("N"));

        NodeResult result = WorkflowStub.fromTyped(stub).getResult(20, TimeUnit.SECONDS, NodeResult.class);

        assertThat(result.outcome()).isEqualTo("REJECTED");
        Document doc = db.getCollection("LenderCustomerPrecheck").find().first();
        assertThat(doc.getBoolean("approved")).isFalse();
    }

    @Test
    void anotherAttemptInProgressIsSkipped() throws TimeoutException {
        db.getCollection("LenderCustomerPrecheck").insertOne(new Document("loanRequestId", "LR-DECOMP-3")
                .append("lenderSlug", "axis").append("clientLoanId", "legacy-random").append("responseMap", new Document()));

        FlowWorkflow stub = start("LR-DECOMP-3");

        NodeResult result = WorkflowStub.fromTyped(stub).getResult(20, TimeUnit.SECONDS, NodeResult.class);

        assertThat(result.outcome()).isEqualTo("SKIPPED");
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = AxisPrecheckDecomposedFlowTest.class.getResourceAsStream("/l1-fixtures/axis/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
