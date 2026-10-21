package com.rupeek.l1.dev;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.mongodb.client.MongoDatabase;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole Axis precheck, end to end: the reference L2 workflow on a (time-skipping) Temporal test server,
 * real L1 ({@code RunTask}) with real Mongo, and Core / Axis / rupeekweb stubbed over HTTP.
 */
class ReferenceAxisPrecheckFlowTest {

    private static final String AXIS = "/investor_integration/actions/invoke_api";
    private static final String VERIFY = "/api/loanrequest/precheck-verification";
    private static final String FAILED = "/api/lender/updateprecheckfailure";

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
        // first poll: ramp still WIP; later polls: all clear
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
        Worker worker = temporal.newWorker("lih-l1");
        worker.registerWorkflowImplementationTypes(ReferenceAxisPrecheckWorkflowImpl.class);
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

    private ReferenceAxisPrecheckWorkflow start(String loanRequestId, Map<String, Object> callback) {
        ReferenceAxisPrecheckWorkflow wf = client.newWorkflowStub(ReferenceAxisPrecheckWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue("lih-l1").setWorkflowId("axis-precheck-" + loanRequestId).build());
        WorkflowClient.start(wf::run, new ReferenceAxisPrecheckWorkflow.Start(loanRequestId, "fresh", 1, null));
        if (callback != null) {
            wf.timbleCallback(callback);
        }
        return wf;
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
    void approvedEndToEnd() throws InterruptedException {
        ReferenceAxisPrecheckWorkflow wf = start("LR-E2E-1", timble("Y"));
        String result = io.temporal.client.WorkflowStub.fromTyped(wf).getResult(String.class);

        assertThat(result).isEqualTo("APPROVED");
        assertThat(wf.history()).containsExactly("axis.precheck.dedupe=INITIATED", "axis.precheck.timble=INITIATED",
                "axis.precheck.timbleCallback=TIMBLE_OK", "axis.precheck.status=PENDING", "axis.precheck.status=SUCCESS",
                "precheck.notify.verified=NOTIFIED");

        String clientLoanId = RkFunctions.uuidFrom("LR-E2E-1:r1:axis-dedupe");
        http.verify(1, postRequestedFor(urlPathEqualTo(AXIS)).withQueryParam("type", equalTo("dedupe_timble"))
                .withRequestBody(matchingJsonPath("$.client_loan_id", equalTo(clientLoanId))));
        http.verify(2, postRequestedFor(urlPathEqualTo(AXIS)).withQueryParam("type", equalTo("dedupe_status"))
                .withRequestBody(matchingJsonPath("$.application_id", equalTo("APP-TEST-9")))
                .withRequestBody(matchingJsonPath("$.client_loan_id", equalTo(clientLoanId))));
        http.verify(1, postRequestedFor(urlPathEqualTo(VERIFY)).withHeader("Authorization", equalTo("admin-token"))
                .withRequestBody(equalTo("loanrequestId=LR-E2E-1&approved=true")));

        Document doc = db.getCollection("LenderCustomerPrecheck").find().first();
        assertThat(doc.getBoolean("approved")).isTrue();
        assertThat(doc.getString("clientLoanId")).isEqualTo(clientLoanId);
        assertThat(doc.getString("reference_id")).isEqualTo("TIMBLE-REF-1");
        Document responseMap = doc.get("responseMap", Document.class);
        assertThat(responseMap.get("axisPreCheckStatus", Document.class).getString("status")).isEqualTo("SUCCESS");
        assertThat(responseMap.get("timble", Document.class).getString("status")).isEqualTo("SUCCESS");
        assertThat(responseMap.get("ramp", Document.class).getString("status")).isEqualTo("SUCCESS");

        Document audit = db.getCollection("LenderCustomerPrecheckAudit").find().first();
        assertThat(audit.getString("precheckStatus")).isEqualTo("success");
        assertThat(audit.getString("remarks")).isEqualTo("PreCheck success for the loanRequest");

        auditWriter.close();
        List<String> calls = db.getCollection(MongoAuditWriter.COLLECTION).find()
                .map(d -> d.getString("requestType")).into(new ArrayList<>());
        assertThat(calls).containsExactlyInAnyOrder("PRECHECK_DEDUPE", "PRECHECK_TIMBLE", "PRECHECK_STATUS", "PRECHECK_STATUS");
    }

    @Test
    void timbleMismatchEndsRejectedAndNotifiesTheFailure() {
        ReferenceAxisPrecheckWorkflow wf = start("LR-E2E-2", timble("N"));
        String result = io.temporal.client.WorkflowStub.fromTyped(wf).getResult(String.class);

        assertThat(result).isEqualTo("REJECTED");
        assertThat(wf.history()).contains("axis.precheck.timbleCallback=TIMBLE_FAILED", "axis.precheck.status=FAILURE",
                "precheck.notify.failed=NOTIFIED");
        http.verify(1, postRequestedFor(urlPathEqualTo(FAILED))
                .withRequestBody(matchingJsonPath("$.loanRequestId", equalTo("LR-E2E-2")))
                .withRequestBody(matchingJsonPath("$.lenderSlug", equalTo("axis")))
                .withRequestBody(matchingJsonPath("$.lendercustomerprofile", containing("\"timbleReferenceId\":\"TIMBLE-REF-1\""))));
        http.verify(0, postRequestedFor(urlPathEqualTo(VERIFY)));
        Document doc = db.getCollection("LenderCustomerPrecheck").find().first();
        assertThat(doc.getBoolean("approved")).isFalse();
        assertThat(doc.get("responseMap", Document.class).get("timble", Document.class).getString("statusMessage"))
                .isEqualTo("Timble Precheck Failure : {\"aadhar_match\":\"Y\",\"name_match\":\"N\"}");
    }

    @Test
    void anotherAttemptInProgressIsSkipped() {
        db.getCollection("LenderCustomerPrecheck").insertOne(new Document("loanRequestId", "LR-E2E-3")
                .append("lenderSlug", "axis").append("clientLoanId", "legacy-random").append("responseMap", new Document()));
        ReferenceAxisPrecheckWorkflow wf = start("LR-E2E-3", null);
        assertThat(io.temporal.client.WorkflowStub.fromTyped(wf).getResult(String.class)).isEqualTo("SKIPPED");
        http.verify(0, postRequestedFor(urlPathEqualTo(AXIS)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = ReferenceAxisPrecheckFlowTest.class.getResourceAsStream("/l1-fixtures/axis/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
