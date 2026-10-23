package com.rupeek.flow.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mongodb.client.MongoDatabase;
import com.rupeek.flow.audit.MongoAuditWriter;
import com.rupeek.flow.expr.fn.RkFunctions;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.NodeResult;
import com.rupeek.flow.registry.FlowDefLoader;
import com.rupeek.flow.registry.TestTaskRegistry;
import com.rupeek.flow.registry.activity.ResolveFlowActivity;
import com.rupeek.flow.task.FlowMongoTestSupport;
import com.rupeek.flow.task.TaskActivityImpl;
import com.rupeek.flow.task.TestNativeExecutors;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real decomposed Axis flow against WireMock and asserts the contract LenderCore would have used --
 * URLs, query params, headers, bodies, Mongo documents -- plus the behaviours this work corrected
 * (docs/axis-parity.md). WireMock stands in for Axis/core/rupeekweb; the flow-config under test is the real one.
 */
class AxisParityFlowTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AXIS = "/investor_integration/actions/invoke_api";
    private static final String CORE = "/api/support/fetchloandetailsv2";
    private static final String VERIFY = "/api/loanrequest/precheck-verification";
    private static final String FAILED = "/api/lender/updateprecheckfailure";
    private static final String QUEUE = "lih-l2";

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
        http.stubFor(get(urlPathEqualTo(CORE)).willReturn(json(fixture("core-customer-response.json"))));
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

        db = FlowMongoTestSupport.client().getDatabase("lendercore");
        for (String c : List.of("LenderCustomerPrecheck", "LenderCustomerPrecheckAudit", MongoAuditWriter.COLLECTION)) {
            db.getCollection(c).drop();
        }
        auditWriter = new MongoAuditWriter(db, 100);
        Map<String, String> env = Map.of("CORE_BASEURL", http.baseUrl(), "CORE_TOKEN", "core-token",
                "AXIS_PRECHECK_BASEURL", http.baseUrl(), "AXIS_PRECHECK_API_KEY", "axis-key",
                "AXIS_PRECHECK_AGREEMENT_ID", "AGR-1",
                "RUPEEKWEB_BASEURL", http.baseUrl(), "RUPEEKWEB_ADMIN_TOKEN", "admin-token");
        TaskActivityImpl taskActivity = TestNativeExecutors.build(env, FlowMongoTestSupport.client(), "lendercore", auditWriter);
        TestTaskRegistry taskRegistry = TestTaskRegistry.publishAll();

        temporal = TestWorkflowEnvironment.newInstance();
        Worker worker = temporal.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(FlowWorkflowImpl.class);
        worker.registerActivitiesImplementations(taskActivity, taskRegistry.fetchTaskActivity, taskRegistry.resolveTaskActivity,
                (ResolveFlowActivity) refs -> refs.stream().collect(java.util.stream.Collectors.toMap(r -> r, r -> 1)));
        temporal.start();
        client = temporal.getWorkflowClient();

        registry = Map.of(
                "axis-precheck-decomposed-dedupe", flow("axis-precheck-decomposed-dedupe"),
                "axis-precheck-decomposed-timble", flow("axis-precheck-decomposed-timble"),
                "axis-precheck-decomposed-timbleCallback", flow("axis-precheck-decomposed-timbleCallback"),
                "axis-precheck-decomposed-status", flow("axis-precheck-decomposed-status"),
                "axis-precheck-decomposed-status-persist", flow("axis-precheck-decomposed-status-persist"),
                "axis-precheck-decomposed-notifyFail", flow("axis-precheck-decomposed-notifyFail"),
                "axis-precheck-decomposed-coreAndStableId", flow("axis-precheck-decomposed-coreAndStableId"));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        temporal.close();
        auditWriter.close();
        http.stop();
    }

    private static FlowDef flow(String ref) {
        return FlowDefLoader.loadFromClasspath("/flow-config/flows/" + ref + ".json");
    }

    private FlowWorkflow start(String loanRequestId, int runNo) {
        FlowWorkflow stub = client.newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId("axis-parity-" + loanRequestId + "-" + runNo)
                        .setWorkflowExecutionTimeout(Duration.ofHours(6)).build());
        Map<String, Object> inputs = Map.of("loanRequestId", loanRequestId, "runNo", runNo, "loanProcess", "fresh");
        WorkflowClient.start(stub::run, FlowRunRequest.root(flow("axis-precheck-decomposed"), inputs, registry));
        return stub;
    }

    private static NodeResult await(FlowWorkflow stub) throws TimeoutException {
        return WorkflowStub.fromTyped(stub).getResult(30, TimeUnit.SECONDS, NodeResult.class);
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

    private List<LoggedRequest> axisCalls(String type) {
        return http.findAll(postRequestedFor(urlPathEqualTo(AXIS)).withQueryParam("type", equalTo(type)));
    }

    private List<Document> auditRows(String requestType) throws InterruptedException {
        auditWriter.close();   // drain the async writer
        return db.getCollection(MongoAuditWriter.COLLECTION).find(new Document("requestType", requestType)).into(new java.util.ArrayList<>());
    }

    // ------------------------------------------------------------------------------------------ happy path

    @Test
    void approvedPathSendsLenderCoresExactRequestsAndWritesItsDocuments() throws Exception {
        long t0 = temporal.currentTimeMillis();
        FlowWorkflow stub = start("LR-PARITY-1", 1);
        stub.event("timbleCallback", timble("Y"));

        assertThat(await(stub).outcome()).isEqualTo("APPROVED");

        String clientLoanId = RkFunctions.uuidFrom("LR-PARITY-1:r1:axis-dedupe");

        // core: Core.getCoreCustomerData (client/Core.java:42-63) -- query order, Authorization, Accept
        LoggedRequest coreCall = http.findAll(getRequestedFor(urlPathEqualTo(CORE))).get(0);
        assertThat(coreCall.getUrl()).isEqualTo(CORE + "?loanRequestIds=LR-PARITY-1&loanIds=&lpLoanIds=&uLoanIds=&uLpLoanIds=");
        assertThat(coreCall.getHeader("Authorization")).isEqualTo("core-token");
        assertThat(coreCall.getHeader("Accept")).isEqualTo("application/json");

        // axis dedupe + timble: AxisClient.java:193-254
        JsonNode expectedBody = JSON.readTree("""
                {"agreement_id":"AGR-1","customer_id":"CUST-TEST-1","application_id":null,"client_loan_id":"%s",
                 "model_name":"CLM1","customer_type":"individual","first_name":"RAVI","middle_name":"KUMAR","last_name":"SHARMA",
                 "name_as_per_pan":"RAVI KUMAR SHARMA","date_of_birth":"1990-01-05","date_of_birth_as_per_pan":"1990-01-05",
                 "mobile_number":"9000000001","email":"ravi.test@example.com","gender":"M","dedupe_type":"single",
                 "aadhar_number":"000011112222",
                 "dedupe_ids":[{"id_type":"PAN","id_value":"ABCDE1234F"},{"id_type":"AADHAR","id_value":"000011112222"}],
                 "address":[
                  {"address_type":"PERMANENT","address_value":"12, MG Road Bengaluru","address_city":"Bengaluru","address_state":"Karnataka","address_pincode":"560001"},
                  {"address_type":"COMMUNICATION","address_value":"12, MG Road Bengaluru","address_city":"Mysuru","address_state":"Karnataka","address_pincode":"570001"}]}
                """.formatted(clientLoanId));
        for (String type : List.of("dedupe", "dedupe_timble")) {
            List<LoggedRequest> calls = axisCalls(type);
            assertThat(calls).as(type).hasSize(1);
            LoggedRequest call = calls.get(0);
            assertThat(call.getUrl()).isEqualTo(AXIS + "?type=" + type);
            assertThat(call.getHeader("API-key")).isEqualTo("axis-key");
            assertThat(call.getHeader("Content-Type")).isEqualTo("application/json");
            assertThat(call.containsHeader("Product-Id")).as("legacy sends no Product-Id on precheck").isFalse();
            assertThat(JSON.readTree(call.getBodyAsString())).as(type).isEqualTo(expectedBody);
        }

        // status: polled until the WIP clears, with the poll delay between (virtual time) -- AxisClient.java:257-281
        List<LoggedRequest> statusCalls = axisCalls("dedupe_status");
        assertThat(statusCalls).hasSize(2);
        assertThat(statusCalls.get(0).getHeader("API-key")).isEqualTo("axis-key");
        JsonNode statusBody = JSON.readTree(statusCalls.get(0).getBodyAsString());
        assertThat(statusBody.get("agreement_id").asText()).isEqualTo("AGR-1");
        assertThat(statusBody.get("customer_id").asText()).isEqualTo("CUST-TEST-1");
        assertThat(statusBody.get("application_id").asText()).isEqualTo("APP-TEST-9");
        assertThat(statusBody.get("client_loan_id").asText()).isEqualTo(clientLoanId);
        assertThat(statusBody.get("first_name").isNull()).isTrue();
        assertThat(temporal.currentTimeMillis() - t0).isGreaterThanOrEqualTo(60_000L);

        // rupeekweb: RupeekWebServiceProvider.updatePrecheckVerificationStatus (53-72)
        List<LoggedRequest> verify = http.findAll(postRequestedFor(urlPathEqualTo(VERIFY)));
        assertThat(verify).hasSize(1);
        assertThat(verify.get(0).getHeader("Authorization")).isEqualTo("admin-token");
        assertThat(verify.get(0).getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded");
        assertThat(verify.get(0).getBodyAsString()).isEqualTo("loanrequestId=LR-PARITY-1&approved=true");
        assertThat(http.findAll(postRequestedFor(urlPathEqualTo(FAILED)))).isEmpty();

        // LenderCustomerPrecheck: PrecheckEntity.java field/collection names
        Document doc = db.getCollection("LenderCustomerPrecheck").find().first();
        assertThat(doc.getString("loanRequestId")).isEqualTo("LR-PARITY-1");
        assertThat(doc.getString("customerId")).isEqualTo("CUST-TEST-1");
        assertThat(doc.getString("lenderSlug")).isEqualTo("axis");
        assertThat(doc.getString("precheckStatus")).isEqualTo("processed");
        assertThat(doc.getString("applicantId")).isEqualTo("APP-TEST-9");
        assertThat(doc.getString("clientLoanId")).isEqualTo(clientLoanId);
        assertThat(doc.getString("reference_id")).isEqualTo("TIMBLE-REF-1");
        assertThat(doc.getBoolean("approved")).isTrue();
        Document rm = doc.get("responseMap", Document.class);
        assertThat(rm.keySet()).containsExactlyInAnyOrder("axisPreCheckStatus", "dedupe", "pan", "ramp", "hunter", "timble");
        assertThat(rm.get("ramp", Document.class)).isEqualTo(new Document("status", "SUCCESS").append("statusMessage", ""));
        assertThat(rm.get("timble", Document.class).getString("statusMessage")).isEqualTo("Timble Precheck Successful");

        // LenderCustomerPrecheckAudit: one row per invocation, ending at the status outcome (PrecheckService.java:655)
        List<Document> audits = db.getCollection("LenderCustomerPrecheckAudit").find().into(new java.util.ArrayList<>());
        assertThat(audits).hasSize(1);
        Document audit = audits.get(0);
        assertThat(audit.getString("precheckStatus")).isEqualTo("success");
        assertThat(audit.getString("remarks")).isEqualTo("PreCheck success for the loanRequest");
        assertThat(audit.getString("loanProcess")).isEqualTo("fresh");
        assertThat(audit.getString("lenderSlug")).isEqualTo("axis");
        assertThat(audit.get("responseMap", Document.class).keySet())
                .containsExactlyInAnyOrder("axisPreCheckStatus", "dedupe", "pan", "ramp", "hunter", "timble");

        // LenderApiAuditLog: lenderSlug/loanRequestId/customerId are what LenderCore's aspect records (AxisApiAuditAspect)
        for (String type : List.of("PRECHECK_DEDUPE", "PRECHECK_TIMBLE", "PRECHECK_STATUS")) {
            List<Document> rows = auditRows(type);
            assertThat(rows).as(type).isNotEmpty();
            for (Document row : rows) {
                assertThat(row.getString("lenderSlug")).isEqualTo("axis");
                assertThat(row.getString("loanRequestId")).isEqualTo("LR-PARITY-1");
                assertThat(row.getString("customerId")).isEqualTo("CUST-TEST-1");
                assertThat(row.getString("status")).isEqualTo("SUCCESS");
                assertThat(row.getString("responseCode")).isEqualTo("200");
            }
        }
    }

    // ------------------------------------------------------------------------------------------ failure path

    @Test
    void failureNotifiesRupeekWebWithTheLenderCoreProfileShape() throws Exception {
        FlowWorkflow stub = start("LR-PARITY-2", 1);
        stub.event("timbleCallback", timble("N"));

        assertThat(await(stub).outcome()).isEqualTo("REJECTED");

        // RupeekWebServiceProvider.updatePrecheckFailureResponse (83-100)
        List<LoggedRequest> failed = http.findAll(postRequestedFor(urlPathEqualTo(FAILED)));
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).getHeader("Authorization")).isEqualTo("admin-token");
        JsonNode body = JSON.readTree(failed.get(0).getBodyAsString());
        assertThat(body.get("loanRequestId").asText()).isEqualTo("LR-PARITY-2");
        assertThat(body.get("lenderSlug").asText()).isEqualTo("axis");
        assertThat(body.get("lendercustomerprofile").isTextual()).as("profile is a JSON string").isTrue();
        JsonNode profile = JSON.readTree(body.get("lendercustomerprofile").asText());
        assertThat(profile.get("approved").asBoolean()).isFalse();
        assertThat(profile.get("lenderSlug").asText()).isEqualTo("axis");
        assertThat(profile.get("responseMap").get("axisPreCheckStatus").get("status").asText()).isEqualTo("FAILURE");
        assertThat(profile.get("responseMap").get("timble").get("statusMessage").asText())
                .isEqualTo("Timble Precheck Failure : {\"aadhar_match\":\"Y\",\"name_match\":\"N\"}");
        assertThat(http.findAll(postRequestedFor(urlPathEqualTo(VERIFY)))).isEmpty();
    }

    @Test
    void aPriorFailedResultEndsRejectedNotApproved() throws Exception {
        String clientLoanId = RkFunctions.uuidFrom("LR-PARITY-3:r1:axis-dedupe");
        db.getCollection("LenderCustomerPrecheck").insertOne(new Document("loanRequestId", "LR-PARITY-3")
                .append("lenderSlug", "axis").append("customerId", "CUST-TEST-1").append("clientLoanId", clientLoanId)
                .append("applicantId", "APP-OLD").append("reference_id", "TREF-OLD")
                .append("responseMap", new Document("axisPreCheckStatus",
                        new Document("status", "FAILURE").append("statusMessage", "Precheck failure received from lender services : {}"))));

        FlowWorkflow stub = start("LR-PARITY-3", 1);
        stub.event("timbleCallback", timble("Y"));

        assertThat(await(stub).outcome()).isEqualTo("REJECTED");
        assertThat(axisCalls("dedupe_status")).isEmpty();
        assertThat(http.findAll(postRequestedFor(urlPathEqualTo(VERIFY)))).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ dedupe errors

    private void stubDedupeErrorsIn200() throws IOException {
        http.stubFor(post(urlPathEqualTo(AXIS)).withQueryParam("type", equalTo("dedupe"))
                .willReturn(json(fixture("axis-dedupe-errors-in-200.json"))));
    }

    @Test
    void freshRunWithADedupeErrorStillCallsTimbleAndLeavesALenderCoreShapedBareRecord() throws Exception {
        stubDedupeErrorsIn200();
        FlowWorkflow stub = start("LR-PARITY-4", 1);
        stub.event("timbleCallback", timble("Y"));

        assertThat(await(stub).outcome()).isEqualTo("APPROVED");

        // AxisPrecheckProcessor.java:84-86 calls dedupe then timble unconditionally; :154-156 skips the dedupe fields
        assertThat(axisCalls("dedupe")).hasSize(1);
        assertThat(axisCalls("dedupe_timble")).hasSize(1);
        Document doc = db.getCollection("LenderCustomerPrecheck").find().first();
        assertThat(doc.getString("precheckStatus")).as("PrecheckEntity builder default").isEqualTo("no_action");
        assertThat(doc.containsKey("applicantId")).isFalse();
        assertThat(doc.containsKey("clientLoanId")).isFalse();
        assertThat(doc.getString("reference_id")).isEqualTo("TIMBLE-REF-1");
        assertThat(doc.getBoolean("approved")).isTrue();   // the status poll then decides, as in legacy
    }

    @Test
    void rerunWithADedupeErrorAbortsLikeLenderCoreAndLeavesTheRecordUntouched() throws Exception {
        stubDedupeErrorsIn200();
        db.getCollection("LenderCustomerPrecheck").insertOne(new Document("loanRequestId", "LR-PARITY-5")
                .append("lenderSlug", "axis").append("customerId", "CUST-TEST-1").append("clientLoanId", "OLD-RUN-ID")
                .append("applicantId", "APP-OLD").append("reference_id", "TREF-OLD").append("approved", false)
                .append("responseMap", new Document("axisPreCheckStatus",
                        new Document("status", "FAILURE").append("statusMessage", "old"))));

        FlowWorkflow stub = start("LR-PARITY-5", 2);

        assertThat(await(stub).outcome()).isEqualTo("REJECTED");

        // AxisPrecheckProcessor.java:91-96: IllegalStateException before any save, audit row marked failure
        Document doc = db.getCollection("LenderCustomerPrecheck").find().first();
        assertThat(doc.getString("clientLoanId")).isEqualTo("OLD-RUN-ID");
        assertThat(doc.getString("reference_id")).isEqualTo("TREF-OLD");
        assertThat(doc.getString("applicantId")).isEqualTo("APP-OLD");
        assertThat(axisCalls("dedupe_timble")).isEmpty();
        Document audit = db.getCollection("LenderCustomerPrecheckAudit").find().first();
        assertThat(audit.getString("precheckStatus")).isEqualTo("failure");
        assertThat(audit.getString("remarks"))
                .isEqualTo("Axis precheck re-run aborted, invalid dedupe or timble response for loanRequestId LR-PARITY-5");
    }

    // ------------------------------------------------------------------------------------------ in progress

    @Test
    void anotherInFlightAttemptIsSkippedWithLenderCoresAuditRow() throws Exception {
        db.getCollection("LenderCustomerPrecheck").insertOne(new Document("loanRequestId", "LR-PARITY-6")
                .append("lenderSlug", "axis").append("customerId", "CUST-TEST-1").append("clientLoanId", "legacy-random")
                .append("responseMap", new Document()));

        FlowWorkflow stub = start("LR-PARITY-6", 1);

        assertThat(await(stub).outcome()).isEqualTo("SKIPPED");
        assertThat(axisCalls("dedupe")).isEmpty();
        Document audit = db.getCollection("LenderCustomerPrecheckAudit").find().first();
        assertThat(audit.getString("precheckStatus")).isEqualTo("skip");
        assertThat(audit.getString("remarks")).isEqualTo("API call skipped - Axis precheck already initiated and not yet completed");
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = AxisParityFlowTest.class.getResourceAsStream("/axis-fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
