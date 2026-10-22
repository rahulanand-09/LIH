package com.rupeek.flow.dev;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.mongodb.client.MongoDatabase;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.NodeResult;
import com.rupeek.flow.registry.FetchFlowActivity;
import com.rupeek.flow.registry.FetchFlowActivityImpl;
import com.rupeek.flow.registry.FlowDefLoader;
import com.rupeek.flow.registry.FlowPublisher;
import com.rupeek.flow.registry.FlowRegistryService;
import com.rupeek.flow.registry.ResolveFlowActivity;
import com.rupeek.flow.registry.ResolveFlowActivityImpl;
import com.rupeek.flow.registry.StoreFlowSource;
import com.rupeek.flow.workflow.FlowRunRequest;
import com.rupeek.flow.workflow.FlowWorkflow;
import com.rupeek.flow.workflow.FlowWorkflowImpl;
import com.rupeek.flow.registry.store.DocType;
import com.rupeek.flow.registry.store.MongoConfigStore;
import com.rupeek.flow.activity.FlowMongoTestSupport;
import com.rupeek.flow.activity.TaskActivityImpl;
import com.rupeek.flow.activity.TestNativeExecutors;
import com.rupeek.flow.audit.MongoAuditWriter;
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
import java.time.Clock;
import java.time.Duration;
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

/**
 * The real versioning proof (docs/flow-lld.md §9): publishes v1 of the {@code coreAndStableId} macro into a
 * real, Mongo-backed registry, runs the WHOLE Axis precheck against the real local Temporal server, publishes v2
 * (adds one harmless audit-tagging step — see {@code flows-v2/axis-precheck-decomposed-coreAndStableId-v2.json}),
 * then runs again. {@code dedupe.json}/{@code timble.json} — the actual callers — are never touched between the
 * two runs; the macro's flowRef is deliberately left OUT of the flat fixture registry both times, so the ONLY way
 * either run resolves it is the real {@code FetchFlowActivity} path, by whatever version is currently latest.
 */
public final class FlowVersioningDemoRunner {

    private static final String AXIS = "/investor_integration/actions/invoke_api";
    private static final String VERIFY = "/api/loanrequest/precheck-verification";
    private static final String FAILED = "/api/lender/updateprecheckfailure";
    private static final String QUEUE = "lih-l1";
    private static final String MACRO_REF = "axis-precheck-decomposed-coreAndStableId";

    public static void main(String[] args) throws Exception {
        WireMockServer http = new WireMockServer(options().dynamicPort());
        http.start();
        stubLender(http);

        MongoDatabase lendercore = FlowMongoTestSupport.client().getDatabase("lendercore");
        for (String c : List.of("LenderCustomerPrecheck", "LenderCustomerPrecheckAudit", MongoAuditWriter.COLLECTION)) {
            lendercore.getCollection(c).drop();
        }
        MongoAuditWriter auditWriter = new MongoAuditWriter(lendercore, 100);
        Map<String, String> env = Map.of("CORE_BASEURL", http.baseUrl(), "CORE_TOKEN", "core-token",
                "AXIS_PRECHECK_BASEURL", http.baseUrl(), "AXIS_PRECHECK_API_KEY", "axis-key",
                "RUPEEKWEB_BASEURL", http.baseUrl(), "RUPEEKWEB_ADMIN_TOKEN", "admin-token");
        TaskActivityImpl taskActivity = TestNativeExecutors.build(env, FlowMongoTestSupport.client(), "lendercore", auditWriter);

        // Flow's own registry: its own lih_flow_config database, not a collection shared with L1's lih_config.
        MongoConfigStore store = new MongoConfigStore(FlowMongoTestSupport.client().getDatabase("lih_flow_config")).ensureIndexes();
        StoreFlowSource flowSource = new StoreFlowSource(store);
        FlowPublisher publisher = new FlowPublisher(store, Clock.systemUTC());

        WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget("127.0.0.1:7233").build());
        WorkflowClient client = WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace("default").build());
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(FlowWorkflowImpl.class);
        worker.registerActivitiesImplementations(taskActivity,
                (ResolveFlowActivity) new ResolveFlowActivityImpl(new FlowRegistryService(flowSource)),
                (FetchFlowActivity) new FetchFlowActivityImpl(flowSource));
        factory.start();
        System.out.println("Worker polling '" + QUEUE + "' on 127.0.0.1:7233 -- registry-backed version resolution is live.");

        // dedupe/timble/timbleCallback/status/status-persist/notifyFail: UNCHANGED, real, committed source --
        // the whole point is these never change between the v1 and v2 runs below. The macro itself is
        // deliberately NOT in this map: its only path to resolving is the real FetchFlowActivity, below.
        Map<String, FlowDef> registry = Map.of(
                "axis-precheck-decomposed-dedupe", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-dedupe.json"),
                "axis-precheck-decomposed-timble", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-timble.json"),
                "axis-precheck-decomposed-timbleCallback", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-timbleCallback.json"),
                "axis-precheck-decomposed-status", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-status.json"),
                "axis-precheck-decomposed-status-persist", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-status-persist.json"),
                "axis-precheck-decomposed-notifyFail", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-notifyFail.json"));
        FlowDef top = FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed.json");

        System.out.println();
        System.out.println("== Publishing v1 of every flow resolveVersions will ever ask the registry about ==");
        // Every SubflowNode ref anywhere in this tree goes through the real ResolveFlowActivity now (that part
        // of the engine is unchanged, already correct, already tested -- VersionPinningAndCycleGuardTest depends
        // on it resolving EVERY ref, flat-registry-backed or not). So every ref needs a published version, even
        // the six whose BODY still comes from the flat fixture registry below and is never re-published again.
        for (String ref : List.of("axis-precheck-decomposed-dedupe", "axis-precheck-decomposed-timble",
                "axis-precheck-decomposed-timbleCallback", "axis-precheck-decomposed-status",
                "axis-precheck-decomposed-status-persist", "axis-precheck-decomposed-notifyFail")) {
            publisher.publish(FlowDefLoader.loadFromClasspath("/flows/" + ref + ".json"), "demo", "initial publish");
        }
        FlowDef macroV1 = FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-coreAndStableId.json");
        publisher.publish(macroV1, "demo", "initial publish");
        System.out.println("published " + MACRO_REF + "@" + macroV1.version() + "; latest = " + store.latest(DocType.FLOW, MACRO_REF));

        run(client, registry, top, "LR-VERSION-DEMO-1");
        long v2TagsBeforePublish = countVersionTags(lendercore);
        System.out.println("version-tag audit entries after the v1 run: " + v2TagsBeforePublish + " (expect 0 -- v1 has no such step)");

        System.out.println();
        System.out.println("== Publishing v2 (adds one audit-tagging step; dedupe.json/timble.json untouched) ==");
        FlowDef macroV2 = FlowDefLoader.loadFromClasspath("/flows-v2/axis-precheck-decomposed-coreAndStableId-v2.json");
        publisher.publish(macroV2, "demo", "bump to v2 -- adds version-tag audit step");
        System.out.println("published " + MACRO_REF + "@" + macroV2.version() + "; latest = " + store.latest(DocType.FLOW, MACRO_REF));

        run(client, registry, top, "LR-VERSION-DEMO-2");
        long v2TagsAfterPublish = countVersionTags(lendercore);
        // 2, not 1: BOTH dedupe and timble reference this same macro, so v2's new step runs from both call
        // sites in the one case -- the point being proven, not a bug: one publish reached every caller.
        System.out.println("version-tag audit entries after the v2 run: " + v2TagsAfterPublish
                + " (expect 2 -- v2's new step ran from BOTH dedupe and timble, with ZERO changes to either source file)");

        factory.shutdown();
        factory.awaitTermination(30, TimeUnit.SECONDS);
        stubs.shutdown();
        auditWriter.close();
        http.stop();
    }

    private static void run(WorkflowClient client, Map<String, FlowDef> registry, FlowDef top, String loanRequestId)
            throws TimeoutException {
        String workflowId = "axis-precheck-decomposed-" + loanRequestId;
        FlowWorkflow stub = client.newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId(workflowId)
                        .setWorkflowExecutionTimeout(Duration.ofHours(1)).build());
        Map<String, Object> inputs = Map.of("loanRequestId", loanRequestId, "runNo", 1, "loanProcess", "fresh");
        WorkflowClient.start(stub::run, FlowRunRequest.root(top, inputs, registry));
        stub.event("timbleCallback", Map.of("product_id", "P1", "customer_id", "CUST-TEST-1",
                "timble_status", "COMPLETED", "name_match", "Y", "aadhar_match", "Y"));
        NodeResult result = WorkflowStub.fromTyped(stub).getResult(60, TimeUnit.SECONDS, NodeResult.class);
        System.out.println(workflowId + " -> " + result.outcome());
    }

    private static long countVersionTags(MongoDatabase db) {
        // precheck.audit.insert@1 maps the recipe's own "status" input to the Mongo field "precheckStatus".
        return db.getCollection("LenderCustomerPrecheckAudit").countDocuments(
                com.mongodb.client.model.Filters.eq("precheckStatus", "v2"));
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
        try (InputStream in = FlowVersioningDemoRunner.class.getResourceAsStream("/axis-fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private FlowVersioningDemoRunner() {
    }
}
