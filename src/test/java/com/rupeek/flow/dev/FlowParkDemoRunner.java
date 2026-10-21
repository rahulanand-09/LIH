package com.rupeek.flow.dev;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.mongodb.client.MongoDatabase;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.NodeResult;
import com.rupeek.flow.registry.FlowDefLoader;
import com.rupeek.flow.registry.ResolveFlowActivity;
import com.rupeek.flow.workflow.FlowRunRequest;
import com.rupeek.flow.workflow.FlowState;
import com.rupeek.flow.workflow.FlowWorkflow;
import com.rupeek.flow.workflow.FlowWorkflowImpl;
import com.rupeek.l1.intercept.MongoAuditWriter;
import com.rupeek.l1.ops.mongo.MongoTestSupport;
import com.rupeek.l1.secrets.EnvSecretResolver;
import com.rupeek.l1.worker.L1Components;
import com.rupeek.l1.worker.RegistryActivityImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Phase 1 of the PARK live demo (docs/flow-lld.md §6): deliberately breaks the first lender/core call so the
 * {@code dedupe} subflow's {@code core} node exhausts its retries and parks for real, against the real local
 * Temporal server. Run this, watch it park (both printed here and in the Web UI), then run
 * {@link FlowResumeDemoRunner} to fix the stub and resume it.
 */
public final class FlowParkDemoRunner {

    private static final String QUEUE = "lih-l1";

    public static void main(String[] args) throws Exception {
        String loanRequestId = args.length > 0 ? args[0] : "LR-PARK-DEMO-1";

        WireMockServer http = new WireMockServer(options().dynamicPort());
        http.start();
        // core.fetchCustomerByLoanRequest is a GET -> idempotent -> 400 classifies as BUSINESS, which is in L1's
        // DoNotRetry set, so the activity fails on its very first attempt instead of waiting through retries.
        http.stubFor(get(urlPathEqualTo("/api/support/fetchloandetailsv2"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"forced failure for the PARK demo\"}")));

        MongoDatabase db = MongoTestSupport.client().getDatabase("lendercore");
        for (String c : List.of("LenderCustomerPrecheck", "LenderCustomerPrecheckAudit", MongoAuditWriter.COLLECTION)) {
            db.getCollection(c).drop();
        }
        MongoAuditWriter auditWriter = new MongoAuditWriter(db, 100);
        Map<String, String> env = Map.of("CORE_BASEURL", http.baseUrl(), "CORE_TOKEN", "core-token",
                "AXIS_PRECHECK_BASEURL", http.baseUrl(), "AXIS_PRECHECK_API_KEY", "axis-key",
                "RUPEEKWEB_BASEURL", http.baseUrl(), "RUPEEKWEB_ADMIN_TOKEN", "admin-token");
        L1Components l1 = L1Components.create(new L1Components.Settings("local", "classpath:l1-config", null,
                Duration.ofSeconds(60), Map.of("lendercore", MongoTestSupport.client()), new EnvSecretResolver(env::get),
                auditWriter, new SimpleMeterRegistry(), ObservationRegistry.NOOP, env::get, Clock.systemUTC()));

        WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget("127.0.0.1:7233").build());
        WorkflowClient client = WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace("default").build());
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(FlowWorkflowImpl.class);
        worker.registerActivitiesImplementations(l1.activity(), new RegistryActivityImpl(l1.registry()),
                (ResolveFlowActivity) refs -> refs.stream().collect(Collectors.toMap(r -> r, r -> 1)));
        factory.start();

        Map<String, FlowDef> registry = Map.of(
                "axis-precheck-decomposed-dedupe", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-dedupe.json"),
                "axis-precheck-decomposed-timble", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-timble.json"),
                "axis-precheck-decomposed-timbleCallback", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-timbleCallback.json"),
                "axis-precheck-decomposed-status", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-status.json"),
                "axis-precheck-decomposed-status-persist", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-status-persist.json"),
                "axis-precheck-decomposed-notifyFail", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-notifyFail.json"),
                "axis-precheck-decomposed-coreAndStableId", FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed-coreAndStableId.json"));
        FlowDef top = FlowDefLoader.loadFromClasspath("/flows/axis-precheck-decomposed.json");

        String workflowId = "axis-precheck-decomposed-" + loanRequestId;
        String dedupeChildId = workflowId + ":dedupe";
        FlowWorkflow stub = client.newWorkflowStub(FlowWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId(workflowId)
                        .setWorkflowExecutionTimeout(Duration.ofHours(1)).build());
        Map<String, Object> inputs = Map.of("loanRequestId", loanRequestId, "runNo", 1, "loanProcess", "fresh");
        WorkflowClient.start(stub::run, FlowRunRequest.root(top, inputs, registry));
        System.out.println("Started " + workflowId + " with a broken core/customer endpoint -- 'core' should park.");
        System.out.println("UI: http://localhost:8233/namespaces/default/workflows/" + dedupeChildId);

        // "core" now lives inside the inline "fetchCoreAndStableId" macro (docs/flow-lld.md "macro" section) --
        // spliced into the dedupe child workflow's OWN execution, so its park key is prefixed by the macro's
        // call-site id, still visible via this same dedupe child's query() (no extra workflow to look in).
        String parkedKey = "fetchCoreAndStableId.core";
        FlowWorkflow dedupe = client.newWorkflowStub(FlowWorkflow.class, dedupeChildId);
        boolean parked = false;
        for (int i = 0; i < 20; i++) {
            Thread.sleep(500);
            try {
                FlowState state = dedupe.query();
                System.out.println("  nodeStatus = " + state.nodeStatus());
                if ("PARKED".equals(state.nodeStatus().get(parkedKey))) {
                    parked = true;
                    break;
                }
            } catch (Exception e) {
                // child workflow not started/visible yet -- keep polling
            }
        }

        if (parked) {
            System.out.println();
            System.out.println("PARKED: dedupe:" + parkedKey + " is stuck, waiting for an operator. The dedupe child workflow");
            System.out.println("(and the top-level workflow blocked on it) are both still RUNNING in Temporal --");
            System.out.println("not failed, not retrying forever. Go look in the UI now: http://localhost:8233");
            System.out.println();
            System.out.println("When ready, run: FlowResumeDemoRunner " + loanRequestId);
        } else {
            System.out.println("Did not observe PARKED within 10s -- check the UI/logs.");
        }

        factory.shutdown();
        factory.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        stubs.shutdown();
        // The parked workflow lives on the Temporal server, independent of this process -- JVM exit here is fine.
        // FlowResumeDemoRunner starts its own fresh worker/Mongo/WireMock and resumes it from there.
    }

    private FlowParkDemoRunner() {
    }
}
