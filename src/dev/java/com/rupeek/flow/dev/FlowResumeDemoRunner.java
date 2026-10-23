package com.rupeek.flow.dev;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.mongodb.client.MongoDatabase;
import com.rupeek.flow.task.FlowMongoTestSupport;
import com.rupeek.flow.task.TaskActivityImpl;
import com.rupeek.flow.task.TestNativeExecutors;
import com.rupeek.flow.audit.MongoAuditWriter;
import com.rupeek.flow.model.NodeResult;
import com.rupeek.flow.registry.activity.ResolveFlowActivity;
import com.rupeek.flow.engine.FlowState;
import com.rupeek.flow.engine.FlowWorkflow;
import com.rupeek.flow.engine.FlowWorkflowImpl;
import com.rupeek.flow.engine.ResumeAction;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Phase 2 of the PARK live demo (docs/flow-lld.md §6): run {@link FlowParkDemoRunner} first to get a real
 * {@code dedupe:core} node parked on the live Temporal server. This fixes the lender endpoint (so the real retry
 * can actually succeed this time), sends the operator {@code resume(RETRY)} signal straight to the parked child
 * workflow's own id -- no registry, found by the same id convention {@code {parent}:{nodeId}} the parent used to
 * start it -- then waits for the whole case to finish.
 */
public final class FlowResumeDemoRunner {

    private static final String AXIS = "/investor_integration/actions/invoke_api";
    private static final String VERIFY = "/api/loanrequest/precheck-verification";
    private static final String FAILED = "/api/lender/updateprecheckfailure";
    private static final String QUEUE = "lih-l1";

    public static void main(String[] args) throws Exception {
        String loanRequestId = args.length > 0 ? args[0] : "LR-PARK-DEMO-1";

        WireMockServer http = new WireMockServer(options().dynamicPort());
        http.start();
        stubLender(http);

        MongoDatabase db = FlowMongoTestSupport.client().getDatabase("lendercore");
        MongoAuditWriter auditWriter = new MongoAuditWriter(db, 100);
        Map<String, String> env = Map.of("CORE_BASEURL", http.baseUrl(), "CORE_TOKEN", "core-token",
                "AXIS_PRECHECK_BASEURL", http.baseUrl(), "AXIS_PRECHECK_API_KEY", "axis-key", "AXIS_PRECHECK_AGREEMENT_ID", "test-agreement",
                "RUPEEKWEB_BASEURL", http.baseUrl(), "RUPEEKWEB_ADMIN_TOKEN", "admin-token");
        TaskActivityImpl taskActivity = TestNativeExecutors.build(env, FlowMongoTestSupport.client(), "lendercore", auditWriter);

        WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget("127.0.0.1:7233").build());
        WorkflowClient client = WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace("default").build());
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(FlowWorkflowImpl.class);
        worker.registerActivitiesImplementations(taskActivity,
                (ResolveFlowActivity) refs -> refs.stream().collect(Collectors.toMap(r -> r, r -> 1)));
        factory.start();

        String workflowId = "axis-precheck-decomposed-" + loanRequestId;
        String dedupeChildId = workflowId + ":dedupe";
        FlowWorkflow dedupe = client.newWorkflowStub(FlowWorkflow.class, dedupeChildId);
        FlowState before = dedupe.query();
        System.out.println("Before resume, dedupe nodeStatus = " + before.nodeStatus());

        String parkedKey = "fetchCoreAndStableId.core";
        System.out.println("Fixed the core/customer endpoint; sending resume(" + parkedKey + ", RETRY) to " + dedupeChildId);
        dedupe.resume(parkedKey, ResumeAction.retry());

        // The flow still has to clear dedupe -> timble -> the waitCallback WAIT node before it can finish, same
        // as FlowDemoRunner's happy path -- the event is buffered regardless of when it arrives (M5), so sending
        // it now is fine even though the workflow hasn't reached WAIT yet.
        FlowWorkflow top = client.newWorkflowStub(FlowWorkflow.class, workflowId);
        Map<String, Object> timble = Map.of("product_id", "P1", "customer_id", "CUST-TEST-1",
                "timble_status", "COMPLETED", "name_match", "Y", "aadhar_match", "Y");
        top.event("timbleCallback", timble);

        NodeResult result = WorkflowStub.fromTyped(top).getResult(60, TimeUnit.SECONDS, NodeResult.class);
        System.out.println("RESULT: " + result.outcome() + " " + result.outputs());

        factory.shutdown();
        factory.awaitTermination(10, TimeUnit.SECONDS);
        stubs.shutdown();
        auditWriter.close();
        http.stop();
    }

    private static void stubLender(WireMockServer http) throws IOException {
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
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = FlowResumeDemoRunner.class.getResourceAsStream("/axis-fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private FlowResumeDemoRunner() {
    }
}
