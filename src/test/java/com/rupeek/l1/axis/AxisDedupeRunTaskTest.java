package com.rupeek.l1.axis;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.rupeek.l1.api.L1FailureType;
import com.rupeek.l1.api.TaskActivity;
import com.rupeek.l1.api.TaskRequest;
import com.rupeek.l1.api.TaskResult;
import com.rupeek.l1.expr.fn.RkFunctions;
import com.rupeek.l1.intercept.MongoAuditWriter;
import com.rupeek.l1.model.Resource;
import com.rupeek.l1.registry.InMemoryResourceProvider;
import com.rupeek.l1.ops.mongo.MongoTestSupport;
import com.rupeek.l1.secrets.EnvSecretResolver;
import com.rupeek.l1.worker.L1Components;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestActivityEnvironment;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Milestone 3 exit check: Axis Dedupe runs end to end through the real {@code RunTask} activity, the classpath
 * config and every executor, against mocked Core and Axis (WireMock) and a real Mongo (Testcontainers).
 */
class AxisDedupeRunTaskTest {

    private static final String AXIS_PATH = "/investor_integration/actions/invoke_api";
    private static final String CORE_PATH = "/api/support/fetchloandetailsv2";
    private static WireMockServer server;

    private MongoDatabase db;
    private MongoCollection<Document> prechecks;
    private MongoAuditWriter auditWriter;
    private L1Components components;
    private TestActivityEnvironment temporal;
    private TaskActivity activity;

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
        db = MongoTestSupport.client().getDatabase("lendercore");
        prechecks = db.getCollection("LenderCustomerPrecheck");
        prechecks.drop();
        db.getCollection(MongoAuditWriter.COLLECTION).drop();
        auditWriter = new MongoAuditWriter(db, 100);

        Map<String, String> env = Map.of(
                "AXIS_PRECHECK_BASEURL", server.baseUrl(),
                "CORE_BASEURL", server.baseUrl(),
                "AXIS_PRECHECK_API_KEY", "test-axis-key",
                "CORE_TOKEN", "test-core-token");
        components = L1Components.create(new L1Components.Settings("local", "classpath:l1-config",
                null, Duration.ofSeconds(60), Map.of("lendercore", MongoTestSupport.client()), new EnvSecretResolver(env::get), auditWriter,
                new SimpleMeterRegistry(), ObservationRegistry.NOOP, env::get, Clock.systemUTC()));

        temporal = TestActivityEnvironment.newInstance();
        temporal.registerActivitiesImplementations(components.activity());
        activity = temporal.newActivityStub(TaskActivity.class, ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .build());

        server.stubFor(get(urlPathEqualTo(CORE_PATH)).withQueryParam("loanRequestIds", equalTo("LR-1"))
                .withHeader("Authorization", equalTo("test-core-token"))
                .willReturn(json(200, fixture("core-customer-response.json"))));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        temporal.close();
        auditWriter.close();
    }

    private static TaskRequest dedupe(String loanRequestId) {
        return new TaskRequest("axis.precheck.dedupe", 1, "axis-precheck-CUST-TEST-1-" + loanRequestId, 1,
                Map.of("loanRequestId", loanRequestId, "runNo", 1));
    }

    private void axisReplies(MappingBuilder request) {
        server.stubFor(request);
    }

    // ------------------------------------------------------------------ happy path and reruns

    @Test
    void freshCaseCallsAxisOnceAndRecordsTheResult() throws Exception {
        axisReplies(post(urlPathEqualTo(AXIS_PATH)).withQueryParam("type", equalTo("dedupe"))
                .willReturn(json(200, fixture("axis-dedupe-success.json"))));

        TaskResult result = activity.runTask(dedupe("LR-1"));

        String expectedClientLoanId = RkFunctions.uuidFrom("LR-1:r1:axis-dedupe");
        assertThat(result.outcome()).isEqualTo("INITIATED");
        assertThat(result.outputs()).isEqualTo(Map.of("applicantId", "APP-TEST-9", "clientLoanId", expectedClientLoanId));

        server.verify(1, postRequestedFor(urlPathEqualTo(AXIS_PATH))
                .withHeader("API-key", equalTo("test-axis-key"))
                .withRequestBody(matchingJsonPath("$.client_loan_id", equalTo(expectedClientLoanId)))
                .withRequestBody(matchingJsonPath("$.first_name", equalTo("RAVI")))
                .withRequestBody(matchingJsonPath("$.last_name", equalTo("SHARMA")))
                .withRequestBody(matchingJsonPath("$.agreement_id", equalTo("local-agreement")))
                .withRequestBody(matchingJsonPath("$.address[0].address_value", equalTo("12, MG Road Bengaluru"))));

        Document doc = prechecks.find(new Document("loanRequestId", "LR-1")).first();
        assertThat(doc.getString("lenderSlug")).isEqualTo("axis");
        assertThat(doc.getString("precheckStatus")).isEqualTo("processed");
        assertThat(doc.getString("applicantId")).isEqualTo("APP-TEST-9");
        assertThat(doc.getString("client_loan_id")).isEqualTo(expectedClientLoanId);
        assertThat(doc.getString("customerId")).isEqualTo("CUST-TEST-1");

        auditWriter.close();
        Document audit = db.getCollection(MongoAuditWriter.COLLECTION).find(new Document("requestType", "PRECHECK_DEDUPE")).first();
        assertThat(audit.getString("status")).isEqualTo("SUCCESS");
        assertThat(audit.getString("loanRequestId")).isEqualTo("LR-1");
        assertThat(audit.getString("customerId")).isEqualTo("CUST-TEST-1");
        assertThat(audit.getString("requestPayload")).doesNotContain("ABCDE1234F", "000011112222").contains("RAVI");
    }

    @Test
    void secondRunOfACompletedCaseReturnsWithoutCallingAxis() {
        axisReplies(post(urlPathEqualTo(AXIS_PATH)).willReturn(json(200, fixture("axis-dedupe-success.json"))));

        activity.runTask(dedupe("LR-1"));
        TaskResult again = activity.runTask(dedupe("LR-1"));

        assertThat(again.outcome()).isEqualTo("ALREADY_INITIATED");
        assertThat(again.outputs().get("applicantId")).isEqualTo("APP-TEST-9");
        server.verify(1, postRequestedFor(urlPathEqualTo(AXIS_PATH)));
    }

    @Test
    void rerunAfterACrashReusesTheStoredClientLoanId() {
        // State left by a run that died after saving intent but before recording Axis's answer
        prechecks.insertOne(new Document("loanRequestId", "LR-1").append("lenderSlug", "axis")
                .append("client_loan_id", "CL-FROM-FIRST-ATTEMPT").append("precheckStatus", "initiated"));
        axisReplies(post(urlPathEqualTo(AXIS_PATH)).willReturn(json(200, fixture("axis-dedupe-success.json"))));

        TaskResult result = activity.runTask(dedupe("LR-1"));

        assertThat(result.outputs().get("clientLoanId")).isEqualTo("CL-FROM-FIRST-ATTEMPT");
        server.verify(postRequestedFor(urlPathEqualTo(AXIS_PATH))
                .withRequestBody(matchingJsonPath("$.client_loan_id", equalTo("CL-FROM-FIRST-ATTEMPT"))));
        assertThat(prechecks.countDocuments()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ failures, as L2 will see them

    @Test
    void lenderRejectionBecomesTheRejectedOutcome() {
        axisReplies(post(urlPathEqualTo(AXIS_PATH)).willReturn(json(200, fixture("axis-dedupe-errors-in-200.json"))));

        TaskResult result = activity.runTask(dedupe("LR-1"));

        assertThat(result.outcome()).isEqualTo("REJECTED");
        assertThat(prechecks.find().first().getString("precheckStatus")).isEqualTo("initiated");
    }

    @Test
    void gatewayErrorIsRetryableTechnical() {
        axisReplies(post(urlPathEqualTo(AXIS_PATH)).willReturn(aResponse().withStatus(503)));
        ApplicationFailure f = failureOf(dedupe("LR-1"));
        assertThat(f.getType()).isEqualTo(L1FailureType.L1_TECHNICAL.name());
        assertThat(f.isNonRetryable()).isFalse();
    }

    @Test
    void serverErrorIsUnknownAndNotRetried() {
        axisReplies(post(urlPathEqualTo(AXIS_PATH)).willReturn(aResponse().withStatus(500)));
        ApplicationFailure f = failureOf(dedupe("LR-1"));
        assertThat(f.getType()).isEqualTo(L1FailureType.L1_UNKNOWN.name());
        assertThat(f.isNonRetryable()).isTrue();
    }

    @Test
    void timeoutAfterSendingIsUnknown() {
        Resource axis = components.resources().resource("axis-precheck");
        ((InMemoryResourceProvider) components.resources()).put(new Resource(axis.resource(), axis.kind(), axis.auth(), axis.headers(),
                new Resource.TimeoutSpec(2000, 300), axis.pool(), axis.env(), null, null, axis.collections()));
        axisReplies(post(urlPathEqualTo(AXIS_PATH)).willReturn(json(200, fixture("axis-dedupe-success.json"))
                .withFixedDelay(1500)));

        ApplicationFailure f = failureOf(dedupe("LR-1"));

        assertThat(f.getType()).isEqualTo(L1FailureType.L1_UNKNOWN.name());
        assertThat(f.getMessage()).contains("step call");
    }

    @Test
    void customerMissingInCoreIsPermanent() {
        server.stubFor(get(urlPathEqualTo(CORE_PATH)).withQueryParam("loanRequestIds", equalTo("LR-404"))
                .willReturn(json(200, "{\"loandata\": []}")));
        ApplicationFailure f = failureOf(dedupe("LR-404"));
        assertThat(f.getType()).isEqualTo(L1FailureType.L1_PERMANENT.name());
        assertThat(f.getMessage()).contains("step core");
        server.verify(0, postRequestedFor(urlPathEqualTo(AXIS_PATH)));
    }

    @Test
    void missingInputIsPermanent() {
        ApplicationFailure f = failureOf(new TaskRequest("axis.precheck.dedupe", 1, "c", 1, Map.of("runNo", 1)));
        assertThat(f.getType()).isEqualTo(L1FailureType.L1_PERMANENT.name());
        assertThat(f.getMessage()).contains("loanRequestId");
    }

    // ------------------------------------------------------------------ helpers

    private ApplicationFailure failureOf(TaskRequest request) {
        try {
            activity.runTask(request);
            throw new AssertionError("expected RunTask to fail");
        } catch (ActivityFailure e) {
            assertThat(e.getCause()).isInstanceOf(ApplicationFailure.class);
            return (ApplicationFailure) e.getCause();
        }
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String fixture(String name) {
        try (InputStream in = AxisDedupeRunTaskTest.class.getResourceAsStream("/l1-fixtures/axis/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
