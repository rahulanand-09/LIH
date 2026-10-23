package com.rupeek.flow.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.rupeek.flow.engine.FlowWorkflowImpl;
import com.rupeek.flow.model.Edge;
import com.rupeek.flow.model.EndNode;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.OnError;
import com.rupeek.flow.model.OperationKind;
import com.rupeek.flow.model.TaskNode;
import com.rupeek.flow.model.WaitNode;
import com.rupeek.flow.registry.FlowPublisher;
import com.rupeek.flow.registry.FlowSource;
import com.rupeek.flow.registry.StoreFlowSource;
import com.rupeek.flow.registry.TaskPublisher;
import com.rupeek.flow.registry.store.InMemoryConfigStore;
import com.rupeek.flow.task.FlowMongoTestSupport;
import com.rupeek.flow.task.TaskActivity;
import com.rupeek.flow.task.TaskResult;
import com.rupeek.flow.task.TestNativeExecutors;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP control surface end to end: MockMvc -> real controllers -> Temporal test server + real flow engine. */
class ApiTest {

    private static final String QUEUE = "api-q";
    private static final String KEY = "k-123";
    private static final ObjectMapper JSON = new ObjectMapper();

    private TestWorkflowEnvironment env;
    private InMemoryConfigStore store;
    private FlowPublisher flowPublisher;
    private TaskPublisher taskPublisher;
    private MockMvc mvc;
    private volatile boolean failTask;
    private ApiProperties props;
    private MongoClient mongo;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(FlowWorkflowImpl.class);
        worker.registerActivitiesImplementations((TaskActivity) request -> {
            if (failTask) {
                throw ApplicationFailure.newFailure("boom", "TECHNICAL");
            }
            return new TaskResult("OK", Map.of());
        });
        env.start();

        mongo = FlowMongoTestSupport.client();
        store = new InMemoryConfigStore();
        flowPublisher = new FlowPublisher(store, Clock.systemUTC());
        var ex = TestNativeExecutors.buildExecutors(Map.of(), mongo, "lendercore", record -> { });
        taskPublisher = new TaskPublisher(store, Clock.systemUTC(), ex.http(), ex.mongo(), ex.map(), ex.gen());
        flowPublisher.publish(new FlowDef("wait-flow", 1, "before", List.of(
                new TaskNode("before", OperationKind.MAP, null, Map.of(), Map.of(), new OnError(1, "PARK")),
                new WaitNode("wait", "cb", null, null),
                new EndNode("done", "DONE")),
                List.of(new Edge("before", "OK", "wait"), new Edge("wait", "RECEIVED", "done"))), "t", "t");
        flowPublisher.publish(new FlowDef("park-flow", 1, "task", List.of(
                new TaskNode("task", OperationKind.MAP, null, Map.of(), Map.of(), new OnError(1, "PARK")),
                new EndNode("done", "DONE")),
                List.of(new Edge("task", "OK", "done"))), "t", "t");

        props = new ApiProperties();
        props.setKey(KEY);
        ApiProperties.CallbackRoute route = new ApiProperties.CallbackRoute();
        route.setEventName("cb");
        route.setWorkflowIdPrefix("wait-flow-");
        props.getCallbacks().put("SRC_PURPOSE", route);
        mvc = buildMvc(props);
    }

    private MockMvc buildMvc(ApiProperties p) {
        FlowSource source = new StoreFlowSource(store);
        CaseService cases = new CaseService(env.getWorkflowClient(), source, QUEUE);
        return MockMvcBuilders.standaloneSetup(new CaseController(cases),
                        new AdminController(flowPublisher, taskPublisher, store),
                        new CallbackController(p, mongo, cases))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new ApiAuthFilter(p.getKey(), p.getCallbackSecret(), true))
                .build();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private JsonNode body(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode awaitStatus(String workflowId, Predicate<JsonNode> ready) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            last = body(mvc.perform(get("/cases/" + workflowId).header("X-API-Key", KEY)).andReturn());
            if (ready.test(last)) {
                return last;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("status never reached expected state, last=" + last);
    }

    @Test
    void startIsAcceptedThenDeduplicatedWhileRunning() throws Exception {
        MvcResult first = mvc.perform(json(post("/flows/wait-flow/start"), "{\"businessKey\":\"A1\",\"inputs\":{\"x\":1}}"))
                .andExpect(status().isAccepted()).andReturn();
        assertThat(body(first).get("started").asBoolean()).isTrue();
        assertThat(body(first).get("workflowId").asText()).isEqualTo("wait-flow-A1");

        MvcResult second = mvc.perform(json(post("/flows/wait-flow/start"), "{\"businessKey\":\"A1\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(second).get("started").asBoolean()).isFalse();
        assertThat(body(second).get("runId").asText()).isEqualTo(body(first).get("runId").asText());
    }

    @Test
    void startWithoutBusinessKeyGeneratesOneAndRejectsBadInput() throws Exception {
        MvcResult r = mvc.perform(json(post("/flows/wait-flow/start"), "{}")).andExpect(status().isAccepted()).andReturn();
        assertThat(body(r).get("workflowId").asText()).startsWith("wait-flow-").hasSizeGreaterThan(20);

        mvc.perform(json(post("/flows/nope/start"), "{}")).andExpect(status().isNotFound());
        mvc.perform(json(post("/flows/bad$ref/start"), "{}")).andExpect(status().isBadRequest());
        mvc.perform(json(post("/flows/wait-flow/start"), "{\"businessKey\":\"a b\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post("/flows/wait-flow/start"), "{not json")).andExpect(status().isBadRequest());
    }

    @Test
    void signalCompletesTheCaseAndStatusReportsOutcome() throws Exception {
        mvc.perform(json(post("/flows/wait-flow/start"), "{\"businessKey\":\"S1\"}")).andExpect(status().isAccepted());
        awaitStatus("wait-flow-S1", s -> "RUNNING".equals(s.get("status").asText()));

        mvc.perform(json(post("/cases/wait-flow-S1/events/cb"), "{\"flag\":\"Y\"}")).andExpect(status().isOk());

        JsonNode done = awaitStatus("wait-flow-S1", s -> "COMPLETED".equals(s.get("status").asText()));
        assertThat(done.get("outcome").asText()).isEqualTo("DONE");

        mvc.perform(json(post("/cases/wait-flow-S1/events/cb"), "{}")).andExpect(status().isConflict());
        mvc.perform(json(post("/cases/ghost/events/cb"), "{}")).andExpect(status().isNotFound());
        mvc.perform(get("/cases/ghost").header("X-API-Key", KEY)).andExpect(status().isNotFound());
        mvc.perform(json(post("/cases/wait-flow-S1/events/bad$name"), "{}")).andExpect(status().isBadRequest());
    }

    @Test
    void parkedCaseShowsParkedAndResumeRetryCompletesIt() throws Exception {
        failTask = true;
        mvc.perform(json(post("/flows/park-flow/start"), "{\"businessKey\":\"P1\"}")).andExpect(status().isAccepted());
        JsonNode parked = awaitStatus("park-flow-P1", s -> "PARKED".equals(s.get("status").asText()));
        assertThat(parked.get("parkedNodes").get(0).asText()).isEqualTo("task");

        mvc.perform(json(post("/cases/park-flow-P1/resume"), "{\"nodeId\":\"task\",\"action\":\"SKIP\"}"))
                .andExpect(status().isBadRequest());   // SKIP needs an outcome
        failTask = false;
        mvc.perform(json(post("/cases/park-flow-P1/resume"), "{\"nodeId\":\"task\",\"action\":\"RETRY\"}"))
                .andExpect(status().isOk());
        JsonNode done = awaitStatus("park-flow-P1", s -> "COMPLETED".equals(s.get("status").asText()));
        assertThat(done.get("outcome").asText()).isEqualTo("DONE");
    }

    @Test
    void callbackFindsNewestPrecheckRecordAndSignalsItsCase() throws Exception {
        var coll = mongo.getDatabase("lendercore").getCollection("LenderCustomerPrecheck");
        coll.insertOne(new Document("customerId", "C-9").append("lenderSlug", "axis").append("loanRequestId", "OLD")
                .append("creation_date", new Date(1_000)));
        coll.insertOne(new Document("customerId", "C-9").append("lenderSlug", "axis").append("loanRequestId", "NEW")
                .append("creation_date", new Date(2_000)));
        mvc.perform(json(post("/flows/wait-flow/start"), "{\"businessKey\":\"NEW\"}")).andExpect(status().isAccepted());

        mvc.perform(post("/v1/callback?source=SRC&purpose=PURPOSE").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customer_id\":\"C-9\",\"name_match\":\"Y\"}"))
                .andExpect(status().isOk());
        awaitStatus("wait-flow-NEW", s -> "COMPLETED".equals(s.get("status").asText()));

        mvc.perform(post("/v1/callback?source=SRC&purpose=PURPOSE").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customer_id\":\"nobody\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/v1/callback?source=SRC&purpose=PURPOSE").contentType(MediaType.APPLICATION_JSON)
                        .content("{}")).andExpect(status().isBadRequest());
        // unknown source/purpose: LenderCore's default processor acknowledges without acting
        mvc.perform(post("/v1/callback?source=X&purpose=Y").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void apiKeyIsRequiredAndCallbackUsesItsOwnSecret() throws Exception {
        mvc.perform(post("/flows/wait-flow/start").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/flows/wait-flow/start").header("X-API-Key", "wrong").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/flows/wait-flow")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/flows/wait-flow").header("X-API-Key", KEY)).andExpect(status().isOk());
        // the API key does not open the callback when a secret is configured, and vice versa
        props.setCallbackSecret("cb-secret");
        mvc = buildMvc(props);
        mvc.perform(post("/v1/callback?source=X&purpose=Y").header("X-API-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/callback?source=X&purpose=Y").header("X-Callback-Secret", "cb-secret")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        mvc.perform(get("/cases/x").header("X-Callback-Secret", "cb-secret")).andExpect(status().isUnauthorized());
        // other paths (e.g. /actuator/health) are not guarded by the filter
        mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
    }

    @Test
    void adminPublishesValidTaskRejectsBrokenCelAndReportsLatest() throws Exception {
        String ok = "{\"taskRef\":\"t.ok\",\"version\":1,\"kind\":\"MAP\",\"fields\":{\"template\":{\"outcome\":\"OK\"}}}";
        mvc.perform(json(post("/admin/tasks"), ok)).andExpect(status().isCreated());
        JsonNode info = body(mvc.perform(get("/admin/tasks/t.ok").header("X-API-Key", KEY)).andExpect(status().isOk()).andReturn());
        assertThat(info.get("latest").asInt()).isEqualTo(1);

        mvc.perform(json(post("/admin/tasks"), ok)).andExpect(status().isConflict());   // versions are immutable

        String bad = "{\"taskRef\":\"t.bad\",\"version\":1,\"kind\":\"MAP\",\"fields\":{\"template\":{\"outcome\":\"=1 ?\"}}}";
        MvcResult r = mvc.perform(json(post("/admin/tasks"), bad)).andExpect(status().isBadRequest()).andReturn();
        assertThat(body(r).get("message").asText()).contains("CEL");
        mvc.perform(get("/admin/tasks/t.bad").header("X-API-Key", KEY)).andExpect(status().isNotFound());

        String flow = "{\"flowRef\":\"f.new\",\"version\":2,\"start\":\"e\",\"nodes\":[{\"nodeType\":\"END\",\"id\":\"e\",\"status\":\"X\"}],\"edges\":[]}";
        mvc.perform(json(post("/admin/flows"), flow)).andExpect(status().isCreated());
        assertThat(body(mvc.perform(get("/admin/flows/f.new").header("X-API-Key", KEY)).andReturn()).get("latest").asInt())
                .isEqualTo(2);
    }

    @Test
    void seederPublishesClasspathConfigOnceAndNeverOverwrites() {
        InMemoryConfigStore fresh = new InMemoryConfigStore();
        var ex = TestNativeExecutors.buildExecutors(Map.of(), mongo, "lendercore", record -> { });
        RegistrySeeder seeder = new RegistrySeeder(fresh, new FlowPublisher(fresh, Clock.systemUTC()),
                new TaskPublisher(fresh, Clock.systemUTC(), ex.http(), ex.mongo(), ex.map(), ex.gen()));

        int[] first = seeder.seed();
        assertThat(first[0]).isGreaterThan(0);
        assertThat(first[1]).isGreaterThan(0);
        assertThat(first[2]).isZero();

        int[] second = seeder.seed();
        assertThat(second[0]).isZero();
        assertThat(second[1]).isZero();
        assertThat(second[2]).isEqualTo(first[0] + first[1]);
    }
}
