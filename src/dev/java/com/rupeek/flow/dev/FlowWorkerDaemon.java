package com.rupeek.flow.dev;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.TaskDef;
import com.rupeek.flow.registry.activity.FetchFlowActivity;
import com.rupeek.flow.registry.activity.FetchFlowActivityImpl;
import com.rupeek.flow.registry.activity.FetchTaskActivity;
import com.rupeek.flow.registry.activity.FetchTaskActivityImpl;
import com.rupeek.flow.registry.FlowDefLoader;
import com.rupeek.flow.registry.FlowPublisher;
import com.rupeek.flow.registry.FlowRegistryService;
import com.rupeek.flow.registry.activity.ResolveFlowActivity;
import com.rupeek.flow.registry.activity.ResolveFlowActivityImpl;
import com.rupeek.flow.registry.activity.ResolveTaskActivity;
import com.rupeek.flow.registry.activity.ResolveTaskActivityImpl;
import com.rupeek.flow.registry.StoreFlowSource;
import com.rupeek.flow.registry.StoreTaskSource;
import com.rupeek.flow.registry.TaskDefLoader;
import com.rupeek.flow.registry.TaskPublisher;
import com.rupeek.flow.registry.TaskRegistryService;
import com.rupeek.flow.engine.FlowWorkflowImpl;
import com.rupeek.flow.registry.store.DocType;
import com.rupeek.flow.registry.store.MongoConfigStore;
import com.rupeek.flow.task.TaskActivityImpl;
import com.rupeek.flow.task.TestNativeExecutors;
import com.rupeek.flow.audit.MongoAuditWriter;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * The standing version of every one-shot demo runner before this: connects ONCE to the real local Temporal
 * server and a REAL, persistent Mongo (docker container {@code lih-mongo}, {@code mongodb://localhost:27018} —
 * the same instance {@code application.yml} already points at, not Testcontainers), then just keeps polling,
 * same as a real deployment would. {@link FlowTriggerClient} starts/signals individual cases against it; this
 * process owns nothing but the connections.
 *
 * <p>Bootstraps the registry idempotently: every Axis flow ref gets published at v1 on first run only (checked
 * via {@code store.latest(...)} first) — safe to stop and restart this process without a duplicate-version error.
 */
public final class FlowWorkerDaemon {

    private static final String QUEUE = "lih-l1";
    private static final int WIREMOCK_PORT = 18089;
    private static final String AXIS = "/investor_integration/actions/invoke_api";
    private static final String VERIFY = "/api/loanrequest/precheck-verification";
    private static final String FAILED = "/api/lender/updateprecheckfailure";

    public static void main(String[] args) throws Exception {
        WireMockServer http = new WireMockServer(options().port(WIREMOCK_PORT));
        http.start();
        stubLender(http);

        MongoClient mongo = MongoClients.create("mongodb://localhost:27018");
        MongoDatabase lendercore = mongo.getDatabase("lendercore");
        MongoAuditWriter auditWriter = new MongoAuditWriter(lendercore, 100);
        Map<String, String> env = Map.of("CORE_BASEURL", http.baseUrl(), "CORE_TOKEN", "core-token",
                "AXIS_PRECHECK_BASEURL", http.baseUrl(), "AXIS_PRECHECK_API_KEY", "axis-key",
                "RUPEEKWEB_BASEURL", http.baseUrl(), "RUPEEKWEB_ADMIN_TOKEN", "admin-token");
        TestNativeExecutors.Executors executors = TestNativeExecutors.buildExecutors(env, mongo, "lendercore", auditWriter);
        TaskActivityImpl taskActivity = new TaskActivityImpl(executors.http(), executors.mongo(), executors.map(), executors.gen());

        MongoConfigStore store = new MongoConfigStore(mongo.getDatabase("lih_flow_config")).ensureIndexes();
        StoreFlowSource flowSource = new StoreFlowSource(store);
        StoreTaskSource taskSource = new StoreTaskSource(store);
        FlowPublisher flowPublisher = new FlowPublisher(store, Clock.systemUTC());
        TaskPublisher taskPublisher = new TaskPublisher(store, Clock.systemUTC(), executors.http(), executors.mongo(),
                executors.map(), executors.gen());
        bootstrapRegistry(store, flowPublisher);
        bootstrapTaskRegistry(store, taskPublisher);

        WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget("127.0.0.1:7233").build());
        WorkflowClient client = WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace("default").build());
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(FlowWorkflowImpl.class);
        worker.registerActivitiesImplementations(taskActivity,
                (ResolveFlowActivity) new ResolveFlowActivityImpl(new FlowRegistryService(flowSource)),
                (FetchFlowActivity) new FetchFlowActivityImpl(flowSource),
                (ResolveTaskActivity) new ResolveTaskActivityImpl(new TaskRegistryService(taskSource)),
                (FetchTaskActivity) new FetchTaskActivityImpl(taskSource));
        factory.start();

        System.out.println();
        System.out.println("FlowWorkerDaemon is up:");
        System.out.println("  Temporal   127.0.0.1:7233  (UI http://localhost:8233), task queue '" + QUEUE + "'");
        System.out.println("  Mongo      mongodb://localhost:27018  (lendercore + lih_flow_config, real docker container lih-mongo)");
        System.out.println("  WireMock   " + http.baseUrl() + "  (stands in for Axis/core/rupeekweb)");
        System.out.println("  Registry   flows=" + store.pointers(DocType.FLOW) + "  tasks=" + store.pointers(DocType.TASK).size() + " refs");
        System.out.println();
        System.out.println("Trigger a case: FlowTriggerClient <loanRequestId>");
        System.out.println("Stop this process (Ctrl-C) to shut the worker down cleanly.");

        CountDownLatch keepRunning = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            factory.shutdown();
            try {
                factory.awaitTermination(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort on the way out
            }
            stubs.shutdown();
            try {
                auditWriter.close();
            } catch (Exception ignored) {
                // best effort on the way out
            }
            http.stop();
            mongo.close();
            keepRunning.countDown();
        }));
        keepRunning.await();
    }

    /** Publishes v1 of every ref {@code resolveVersions} will ever ask about, but only the first time. */
    private static void bootstrapRegistry(MongoConfigStore store, FlowPublisher publisher) throws IOException {
        for (String ref : List.of("axis-precheck-decomposed-dedupe", "axis-precheck-decomposed-timble",
                "axis-precheck-decomposed-timbleCallback", "axis-precheck-decomposed-status",
                "axis-precheck-decomposed-status-persist", "axis-precheck-decomposed-notifyFail",
                "axis-precheck-decomposed-coreAndStableId")) {
            if (store.latest(DocType.FLOW, ref).isEmpty()) {
                FlowDef flow = FlowDefLoader.loadFromClasspath("/flow-config/flows/" + ref + ".json");
                publisher.publish(flow, "daemon", "bootstrap on first start");
                System.out.println("published " + ref + "@" + flow.version() + " (first run)");
            }
        }
    }

    /** Every {@code flow-config/tasks/*.json} file, published (and CEL-validated) the first time only. */
    private static void bootstrapTaskRegistry(MongoConfigStore store, TaskPublisher publisher) throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        int published = 0;
        for (Resource r : resolver.getResources("classpath:flow-config/tasks/*.json")) {
            TaskDef task;
            try (InputStream in = r.getInputStream()) {
                task = TaskDefLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            if (store.latest(DocType.TASK, task.taskRef()).isEmpty()) {
                publisher.publish(task, "daemon", "bootstrap on first start");
                published++;
            }
        }
        if (published > 0) {
            System.out.println("published " + published + " task(s) (first run)");
        }
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
        try (InputStream in = FlowWorkerDaemon.class.getResourceAsStream("/axis-fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private FlowWorkerDaemon() {
    }
}
