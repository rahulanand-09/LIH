package com.rupeek.flow.dev;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.mongodb.client.MongoDatabase;
import com.rupeek.flow.task.TaskActivityImpl;
import com.rupeek.flow.task.TestNativeExecutors;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.NodeResult;
import com.rupeek.flow.registry.FlowDefLoader;
import com.rupeek.flow.registry.activity.ResolveFlowActivity;
import com.rupeek.flow.registry.TestTaskRegistry;
import com.rupeek.flow.engine.FlowRunRequest;
import com.rupeek.flow.engine.FlowWorkflow;
import com.rupeek.flow.engine.FlowWorkflowImpl;
import com.rupeek.flow.task.FlowMongoTestSupport;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.bson.Document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
 * Runs the fully-decomposed Axis precheck (docs/flow-lld.md §11b) against a REAL local Temporal server
 * (`temporal server start-dev`, localhost:7233/8233) instead of {@code TestWorkflowEnvironment} — same
 * fixtures/wiring as {@code AxisPrecheckDecomposedFlowTest}, so the workflow/child-workflow tree it produces can
 * be inspected live in the Temporal Web UI. Lender calls still go through WireMock (no real Axis endpoint to
 * hit); Mongo is a real Testcontainers instance, same as the test suite.
 *
 * <p>Usage: {@code mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=/tmp/lih-cp.txt &&
 * java -cp target/classes:target/test-classes:$(cat /tmp/lih-cp.txt) com.rupeek.flow.dev.FlowDemoRunner}
 */
public final class FlowDemoRunner {

    private static final String AXIS = "/investor_integration/actions/invoke_api";
    private static final String VERIFY = "/api/loanrequest/precheck-verification";
    private static final String FAILED = "/api/lender/updateprecheckfailure";
    private static final String QUEUE = "lih-l1";

    public static void main(String[] args) throws Exception {
        String loanRequestId = args.length > 0 ? args[0] : "LR-LOCAL-DEMO-1";

        WireMockServer http = new WireMockServer(options().dynamicPort());
        http.start();
        stubLender(http);

        MongoDatabase db = FlowMongoTestSupport.client().getDatabase("lendercore");
        for (String c : List.of("LenderCustomerPrecheck", "LenderCustomerPrecheckAudit",
                com.rupeek.flow.audit.MongoAuditWriter.COLLECTION)) {
            db.getCollection(c).drop();
        }
        com.rupeek.flow.audit.MongoAuditWriter auditWriter = new com.rupeek.flow.audit.MongoAuditWriter(db, 100);
        Map<String, String> env = Map.of("CORE_BASEURL", http.baseUrl(), "CORE_TOKEN", "core-token",
                "AXIS_PRECHECK_BASEURL", http.baseUrl(), "AXIS_PRECHECK_API_KEY", "axis-key", "AXIS_PRECHECK_AGREEMENT_ID", "test-agreement",
                "RUPEEKWEB_BASEURL", http.baseUrl(), "RUPEEKWEB_ADMIN_TOKEN", "admin-token");
        TaskActivityImpl taskActivity = TestNativeExecutors.build(env, FlowMongoTestSupport.client(), "lendercore", auditWriter);
        TestTaskRegistry taskRegistry = TestTaskRegistry.publishAll();

        WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget("127.0.0.1:7233").build());
        WorkflowClient client = WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace("default").build());
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(FlowWorkflowImpl.class);
        worker.registerActivitiesImplementations(taskActivity, taskRegistry.fetchTaskActivity, taskRegistry.resolveTaskActivity,
                (ResolveFlowActivity) refs -> refs.stream().collect(Collectors.toMap(r -> r, r -> 1)));
        factory.start();
        System.out.println("Worker polling '" + QUEUE + "' on 127.0.0.1:7233 (namespace default)");

        Map<String, FlowDef> registry = Map.of(
                "axis-precheck-decomposed-dedupe", FlowDefLoader.loadFromClasspath("/flow-config/flows/axis-precheck-decomposed-dedupe.json"),
                "axis-precheck-decomposed-timble", FlowDefLoader.loadFromClasspath("/flow-config/flows/axis-precheck-decomposed-timble.json"),
                "axis-precheck-decomposed-timbleCallback", FlowDefLoader.loadFromClasspath("/flow-config/flows/axis-precheck-decomposed-timbleCallback.json"),
                "axis-precheck-decomposed-status", FlowDefLoader.loadFromClasspath("/flow-config/flows/axis-precheck-decomposed-status.json"),
                "axis-precheck-decomposed-status-persist", FlowDefLoader.loadFromClasspath("/flow-config/flows/axis-precheck-decomposed-status-persist.json"),
                "axis-precheck-decomposed-notifyFail", FlowDefLoader.loadFromClasspath("/flow-config/flows/axis-precheck-decomposed-notifyFail.json"),
                "axis-precheck-decomposed-coreAndStableId", FlowDefLoader.loadFromClasspath("/flow-config/flows/axis-precheck-decomposed-coreAndStableId.json"));
        FlowDef top = FlowDefLoader.loadFromClasspath("/flow-config/flows/axis-precheck-decomposed.json");

        String workflowId = "axis-precheck-decomposed-" + loanRequestId;
        FlowWorkflow stub = client.newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId(workflowId)
                        .setWorkflowExecutionTimeout(Duration.ofHours(1)).build());
        Map<String, Object> inputs = Map.of("loanRequestId", loanRequestId, "runNo", 1, "loanProcess", "fresh");
        WorkflowClient.start(stub::run, FlowRunRequest.root(top, inputs, registry));
        System.out.println("Started " + workflowId + " -- watch it at http://localhost:8233/namespaces/default/workflows/" + workflowId);

        // The WAIT node buffers an event regardless of when it arrives relative to the node being reached (M5) --
        // firing it immediately after start is fine, no need to poll for the workflow to "be ready".
        Map<String, Object> timble = Map.of("product_id", "P1", "customer_id", "CUST-TEST-1",
                "timble_status", "COMPLETED", "name_match", "Y", "aadhar_match", "Y");
        stub.event("timbleCallback", timble);

        NodeResult result = WorkflowStub.fromTyped(stub).getResult(60, TimeUnit.SECONDS, NodeResult.class);
        System.out.println("RESULT: " + result.outcome() + " " + result.outputs());
        Document doc = db.getCollection("LenderCustomerPrecheck").find().first();
        System.out.println("Mongo doc: " + doc);

        factory.shutdown();
        factory.awaitTermination(30, TimeUnit.SECONDS);
        stubs.shutdown();
        auditWriter.close();
        http.stop();
        System.out.println("Worker stopped; Temporal server keeps the workflow history -- inspect it in the UI above.");
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
        try (InputStream in = FlowDemoRunner.class.getResourceAsStream("/axis-fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private FlowDemoRunner() {
    }
}
