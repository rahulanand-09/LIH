package com.rupeek.flow.workflow;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.mongodb.client.MongoDatabase;
import com.rupeek.flow.model.FlowDef;
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
 * M7: the real Axis precheck — root flow on the new {@code com.rupeek.flow} engine, every node a RECIPE-kind
 * TASK delegating to L1's real, already-proven {@code TaskActivity}/{@code RegistryActivity}. Same fixtures and
 * oracle as {@link com.rupeek.l1.dev.ReferenceAxisPrecheckFlowTest} — this proves the new engine reproduces the
 * same behavior the reference workflow already established, not a second copy of L1's own correctness proof.
 */
class AxisPrecheckFlowTest {

    private static final String AXIS = "/investor_integration/actions/invoke_api";
    private static final String VERIFY = "/api/loanrequest/precheck-verification";
    private static final String FAILED = "/api/lender/updateprecheckfailure";
    private static final String QUEUE = "lih-l1";

    private WireMockServer http;
    private MongoDatabase db;
    private MongoAuditWriter auditWriter;
    private TestWorkflowEnvironment temporal;
    private WorkflowClient client;

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
        worker.registerActivitiesImplementations(l1.activity(), new RegistryActivityImpl(l1.registry()));
        temporal.start();
        client = temporal.getWorkflowClient();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        temporal.close();
        auditWriter.close();
        http.stop();
    }

    /**
     * Loaded from the real config, {@code src/main/resources/flows/axis-precheck.json} — see
     * {@code docs/flow-lld.md} for the full node/edge table. dedupe -&gt; timble -&gt; wait(timbleCallback) -&gt;
     * timbleCallback -&gt; status (self-loop while PENDING) -&gt; notify.verified/notify.failed -&gt; END, mirroring
     * docs/l1-for-l2.md's flow diagram.
     *
     * <p>Two deliberate simplifications left for a human pass later, per docs/l1-for-l2.md's own open items: a
     * dedupe/timble {@code REJECTED} routes straight to the rejected END rather than the still-open P17 business
     * decision, and the PENDING self-loop on "status" has no inter-poll delay (fine for a test; a real deployment
     * wants a WAIT/timer between polls, not a busy loop).
     */
    private static FlowDef axisPrecheckFlow() {
        return com.rupeek.flow.registry.FlowDefLoader.loadFromClasspath("/flows/axis-precheck.json");
    }

    private FlowWorkflow start(String loanRequestId) {
        FlowWorkflow stub = client.newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId("axis-precheck-" + loanRequestId).build());
        Map<String, Object> inputs = Map.of("loanRequestId", loanRequestId, "runNo", 1, "loanProcess", "fresh");
        WorkflowClient.start(stub::run, FlowRunRequest.root(axisPrecheckFlow(), inputs, Map.of()));
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
        FlowWorkflow stub = start("LR-FLOW-E2E-1");
        stub.event("timbleCallback", timble("Y"));

        var result = WorkflowStub.fromTyped(stub).getResult(10, TimeUnit.SECONDS, com.rupeek.flow.model.NodeResult.class);

        assertThat(result.outcome()).isEqualTo("APPROVED");

        String clientLoanId = RkFunctions.uuidFrom("LR-FLOW-E2E-1:r1:axis-dedupe");
        Document doc = db.getCollection("LenderCustomerPrecheck").find().first();
        assertThat(doc.getBoolean("approved")).isTrue();
        assertThat(doc.getString("clientLoanId")).isEqualTo(clientLoanId);
        assertThat(doc.getString("reference_id")).isEqualTo("TIMBLE-REF-1");
        Document responseMap = doc.get("responseMap", Document.class);
        assertThat(responseMap.get("axisPreCheckStatus", Document.class).getString("status")).isEqualTo("SUCCESS");
    }

    @Test
    void timbleMismatchEndsRejectedAndNotifiesTheFailure() throws TimeoutException {
        FlowWorkflow stub = start("LR-FLOW-E2E-2");
        stub.event("timbleCallback", timble("N"));

        var result = WorkflowStub.fromTyped(stub).getResult(10, TimeUnit.SECONDS, com.rupeek.flow.model.NodeResult.class);

        assertThat(result.outcome()).isEqualTo("REJECTED");
        Document doc = db.getCollection("LenderCustomerPrecheck").find().first();
        assertThat(doc.getBoolean("approved")).isFalse();
    }

    @Test
    void anotherAttemptInProgressIsSkipped() throws TimeoutException {
        db.getCollection("LenderCustomerPrecheck").insertOne(new Document("loanRequestId", "LR-FLOW-E2E-3")
                .append("lenderSlug", "axis").append("clientLoanId", "legacy-random").append("responseMap", new Document()));

        FlowWorkflow stub = start("LR-FLOW-E2E-3");

        var result = WorkflowStub.fromTyped(stub).getResult(10, TimeUnit.SECONDS, com.rupeek.flow.model.NodeResult.class);

        assertThat(result.outcome()).isEqualTo("SKIPPED");
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = AxisPrecheckFlowTest.class.getResourceAsStream("/l1-fixtures/axis/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
