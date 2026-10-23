package com.rupeek.flow.engine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rupeek.flow.expr.fn.RkFunctions;
import com.rupeek.flow.model.TaskDef;
import com.rupeek.flow.registry.TaskDefLoader;
import com.rupeek.flow.registry.TestTaskRegistry;
import com.rupeek.flow.resource.ClasspathResourceProvider;
import com.rupeek.flow.resource.Resource;
import com.rupeek.flow.resource.ResourceConfigLoader;
import com.rupeek.flow.task.FlowMongoTestSupport;
import com.rupeek.flow.task.TaskActivityImpl;
import com.rupeek.flow.task.TaskRequest;
import com.rupeek.flow.task.TaskResult;
import com.rupeek.flow.task.TestNativeExecutors;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the Axis flow-config to LenderCore's behaviour, step by step (docs/axis-parity.md). Every expected string
 * below is produced by replaying LenderCore's own logic here (its Jackson-annotated {@code ResponseMap}, its
 * {@code HashMap}-ordered timble summary), not typed in by hand, so a wording or key-order drift in the CEL fails.
 */
class AxisParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static TaskActivityImpl tasks;

    @BeforeAll
    static void setUp() {
        // Publishing every flow-config task runs TaskPublisher's CEL validation over all of them.
        TestTaskRegistry.publishAll();
        tasks = TestNativeExecutors.build(Map.of("AXIS_PRECHECK_AGREEMENT_ID", "AGR-1"),
                FlowMongoTestSupport.client(), "lendercore", record -> { });
    }

    // ------------------------------------------------------------------------------------------ LC replicas

    /** LenderCore's entity/ResponseMap.java, field order and class-level annotation verbatim. */
    @JsonInclude(content = JsonInclude.Include.NON_NULL, value = JsonInclude.Include.NON_EMPTY)
    public static class LcResponseMap {
        public String customerAcceptanceStatus;
        public String customerBlacklistStatus;
        public LcStatus axisPreCheckStatus;
        public LcStatus dedupe;
        public LcStatus pan;
        public LcStatus ramp;
        public LcStatus hunter;
        public LcStatus timble;
    }

    public static class LcStatus {
        public String status;
        public String statusMessage;

        LcStatus(String status, String statusMessage) {
            this.status = status;
            this.statusMessage = statusMessage;
        }
    }

    private static TaskResult run(String taskRef, Map<String, Object> inputs) {
        TaskDef def = TaskDefLoader.loadFromClasspath("/flow-config/tasks/" + taskRef + ".json");
        return tasks.execute(new TaskRequest(def.kind(), taskRef, 1, "case", "node", 1, inputs, def.fields()));
    }

    private static Map<String, Object> customer(String proofName) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", "CUST-1");
        c.put("firstName", "Ravi");
        c.put("lastName", "Sharma");
        c.put("customerProofName", proofName);
        c.put("customerPanName", "RAVI KUMAR SHARMA");
        c.put("customerPhone", "9000000001");
        c.put("email", "ravi@example.com");
        c.put("gender", "M");
        c.put("dob", "1990-01-05");
        c.put("aadhar", "000011112222");
        c.put("pan", "ABCDE1234F");
        c.put("customerAddress", "  12,  MG Road \n Bengaluru ");
        c.put("permanentAddress", Map.of("city", "Bengaluru", "state", "Karnataka", "pin", "560001"));
        c.put("presentAddress", Map.of("city", "Mysuru", "state", "Karnataka", "pin", "570001"));
        return c;
    }

    // ------------------------------------------------------------------------------------------ request bodies

    @Test
    void precheckRequestHasLenderCoresExactFieldsAndConstants() throws Exception {
        Map<String, Object> out = run("map.axis.precheckRequest",
                Map.of("customer", customer("Ravi Kumar Sharma"), "clientLoanId", "CLID-1")).outputs();

        // AxisPrecheckRequest.java (@JsonProperty names, CLM1/individual constants) + AxisApiService.java:1206-1243
        assertThat(JSON.readTree(JSON.writeValueAsString(out))).isEqualTo(JSON.readTree("""
                {"agreement_id":"AGR-1","customer_id":"CUST-1","application_id":null,"client_loan_id":"CLID-1",
                 "model_name":"CLM1","customer_type":"individual",
                 "first_name":"RAVI","middle_name":"KUMAR","last_name":"SHARMA","name_as_per_pan":"RAVI KUMAR SHARMA",
                 "date_of_birth":"1990-01-05","date_of_birth_as_per_pan":"1990-01-05","mobile_number":"9000000001",
                 "email":"ravi@example.com","gender":"M","dedupe_type":"single","aadhar_number":"000011112222",
                 "dedupe_ids":[{"id_type":"PAN","id_value":"ABCDE1234F"},{"id_type":"AADHAR","id_value":"000011112222"}],
                 "address":[
                  {"address_type":"PERMANENT","address_value":"12, MG Road Bengaluru","address_city":"Bengaluru","address_state":"Karnataka","address_pincode":"560001"},
                  {"address_type":"COMMUNICATION","address_value":"12, MG Road Bengaluru","address_city":"Mysuru","address_state":"Karnataka","address_pincode":"570001"}]}
                """));
    }

    @Test
    void nameSplitFollowsLenderCoresRuleForEveryPartCount() {
        // AxisApiService.java:1207-1231
        assertThat(names("Ravi")).containsExactly("RAVI", null, ".");
        assertThat(names("Ravi Sharma")).containsExactly("RAVI", null, "SHARMA");
        assertThat(names("ravi kumar sharma")).containsExactly("RAVI", "KUMAR", "SHARMA");
        assertThat(names("a b c d")).containsExactly("A", "B", "C D");
        // blank proof name: raw first/last name, not upper-cased, no middle
        assertThat(names("")).containsExactly("Ravi", null, "Sharma");
    }

    private static List<Object> names(String proof) {
        Map<String, Object> out = run("map.axis.precheckRequest",
                Map.of("customer", customer(proof), "clientLoanId", "x")).outputs();
        List<Object> r = new ArrayList<>();
        r.add(out.get("first_name"));
        r.add(out.get("middle_name"));
        r.add(out.get("last_name"));
        return r;
    }

    @Test
    void statusRequestCarriesOnlyTheThreeIdsPlusDefaults() throws Exception {
        Map<String, Object> doc = Map.of("customerId", "CUST-1", "applicantId", "APP-1", "clientLoanId", "CLID-1");
        Map<String, Object> out = run("map.axis.precheckStatusRequest", Map.of("doc", doc)).outputs();

        // AxisApiService.checkPreCheckStatus (1306-1311) + AxisClient.checkPreCheckStatus agreement id (264)
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("agreement_id", "AGR-1");
        expected.put("customer_id", "CUST-1");
        expected.put("application_id", "APP-1");
        expected.put("client_loan_id", "CLID-1");
        expected.put("model_name", "CLM1");
        expected.put("customer_type", "individual");
        for (String k : List.of("first_name", "middle_name", "last_name", "name_as_per_pan", "date_of_birth",
                "date_of_birth_as_per_pan", "mobile_number", "email", "gender", "dedupe_type", "aadhar_number",
                "dedupe_ids", "address")) {
            expected.put(k, null);
        }
        assertThat(JSON.readTree(JSON.writeValueAsString(out))).isEqualTo(JSON.valueToTree(expected));
    }

    // ------------------------------------------------------------------------------------------ status decision

    private static Map<String, Object> docWithTimble(String status, String message) {
        Map<String, Object> rm = new LinkedHashMap<>();
        if (status != null) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("status", status);
            t.put("statusMessage", message);
            rm.put("timble", t);
        }
        return Map.of("responseMap", rm);
    }

    @Test
    void failureDecisionMessageIsByteIdenticalToLenderCores() throws Exception {
        String timbleMsg = "Timble Precheck Failure : {\"aadhar_match\":\"Y\",\"name_match\":\"N\"}";
        Map<String, Object> in = new HashMap<>(Map.of("dedupe", "Y", "pan", "N", "ramp", "WIP", "hunter", "Y", "timbleFlag", "N"));
        in.put("doc", docWithTimble("FAILURE", timbleMsg));

        TaskResult r = run("axis.decomposed.status.decision", in);

        // PrecheckService.java:338-357
        LcResponseMap temp = new LcResponseMap();
        temp.dedupe = new LcStatus("SUCCESS", "");
        temp.pan = new LcStatus("FAILURE", "");
        temp.ramp = new LcStatus("PENDING", "");
        temp.hunter = new LcStatus("SUCCESS", "");
        temp.timble = new LcStatus("FAILURE", timbleMsg);
        String expected = "Precheck failure received from lender services : " + JSON.writeValueAsString(temp);

        assertThat(r.outcome()).isEqualTo("FAILURE");
        assertThat(((Map<?, ?>) r.outputs().get("overall")).get("statusMessage")).isEqualTo(expected);
        assertThat(((Map<?, ?>) r.outputs().get("overall")).get("status")).isEqualTo("FAILURE");
    }

    @Test
    void failureMessageOmitsTimbleWhenNoCallbackHasLanded() throws Exception {
        Map<String, Object> in = new HashMap<>(Map.of("dedupe", "N", "pan", "Y", "ramp", "Y", "hunter", "Y", "timbleFlag", "WIP"));
        in.put("doc", docWithTimble(null, null));

        TaskResult r = run("axis.decomposed.status.decision", in);

        LcResponseMap temp = new LcResponseMap();
        temp.dedupe = new LcStatus("FAILURE", "");
        temp.pan = new LcStatus("SUCCESS", "");
        temp.ramp = new LcStatus("SUCCESS", "");
        temp.hunter = new LcStatus("SUCCESS", "");
        assertThat(((Map<?, ?>) r.outputs().get("overall")).get("statusMessage"))
                .isEqualTo("Precheck failure received from lender services : " + JSON.writeValueAsString(temp));
    }

    @Test
    void successAndPendingMessagesAndPrecedenceMatchLenderCore() {
        Map<String, Object> ok = new HashMap<>(Map.of("dedupe", "Y", "pan", "Y", "ramp", "Y", "hunter", "Y", "timbleFlag", "Y"));
        ok.put("doc", docWithTimble("SUCCESS", "Timble Precheck Successful"));
        TaskResult success = run("axis.decomposed.status.decision", ok);
        assertThat(success.outcome()).isEqualTo("SUCCESS");
        assertThat(((Map<?, ?>) success.outputs().get("overall")).get("statusMessage")).isEqualTo("PreCheck success for the loanRequest");

        Map<String, Object> wip = new HashMap<>(Map.of("dedupe", "Y", "pan", "Y", "ramp", "WIP", "hunter", "Y", "timbleFlag", "Y"));
        wip.put("doc", docWithTimble("SUCCESS", "x"));
        TaskResult pending = run("axis.decomposed.status.decision", wip);
        assertThat(pending.outcome()).isEqualTo("PENDING");
        assertThat(((Map<?, ?>) pending.outputs().get("overall")).get("statusMessage")).isEqualTo("One of the services is giving WIP");

        // an N beats a WIP (PrecheckService.java:338 before :362)
        Map<String, Object> both = new HashMap<>(Map.of("dedupe", "N", "pan", "Y", "ramp", "WIP", "hunter", "Y", "timbleFlag", "Y"));
        both.put("doc", docWithTimble("SUCCESS", "x"));
        assertThat(run("axis.decomposed.status.decision", both).outcome()).isEqualTo("FAILURE");
    }

    @Test
    void timbleFlagMapsLikePrecheckServiceLines311To321() {
        assertThat(run("axis.decomposed.status.timbleFlag", Map.of("doc", docWithTimble("success", "m"))).outputs().get("value")).isEqualTo("Y");
        assertThat(run("axis.decomposed.status.timbleFlag", Map.of("doc", docWithTimble("FAILURE", "m"))).outputs().get("value")).isEqualTo("N");
        assertThat(run("axis.decomposed.status.timbleFlag", Map.of("doc", docWithTimble(null, null))).outputs().get("value")).isEqualTo("WIP");
    }

    @Test
    void alreadyFinalDistinguishesSuccessFromFailure() {
        assertThat(run("axis.decomposed.status.alreadyFinal", Map.of("doc", finalDoc("SUCCESS"))).outcome()).isEqualTo("ALREADY_FINAL");
        assertThat(run("axis.decomposed.status.alreadyFinal", Map.of("doc", finalDoc("failure"))).outcome()).isEqualTo("ALREADY_FAILED");
        assertThat(run("axis.decomposed.status.alreadyFinal", Map.of("doc", finalDoc("PENDING"))).outcome()).isEqualTo("CONTINUE");
    }

    private static Map<String, Object> finalDoc(String status) {
        return Map.of("responseMap", Map.of("axisPreCheckStatus", Map.of("status", status, "statusMessage", "m")));
    }

    // ------------------------------------------------------------------------------------------ timble callback

    @Test
    void timbleCallbackMessageReplaysLenderCoresHashMapSummary() throws Exception {
        for (Map<String, Object> event : List.of(
                ev("N", "Y", "bad name"), ev("N", "Y", null), ev("Y", "N", null), ev("n", " N ", "x"), ev("N", null, null))) {
            TaskResult r = run("axis.decomposed.timbleCallback.decide", Map.of("event", event));

            // AxisTimbleCallbackProcessor.java:48-75
            Map<String, Object> summary = new HashMap<>();
            for (String k : List.of("name_match", "aadhar_match", "timble_error_message")) {
                if (event.containsKey(k)) {
                    summary.put(k, event.get(k));
                }
            }
            assertThat(r.outputs().get("ok")).isEqualTo(false);
            assertThat(r.outputs().get("status")).isEqualTo("FAILURE");
            assertThat(r.outputs().get("message")).isEqualTo("Timble Precheck Failure : " + JSON.writeValueAsString(summary));
        }
    }

    private static Map<String, Object> ev(String name, String aadhar, String error) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("product_id", "P1");
        e.put("customer_id", "CUST-1");
        e.put("timble_status", "COMPLETED");
        e.put("name_match", name);
        if (aadhar != null) {
            e.put("aadhar_match", aadhar);
        }
        if (error != null) {
            e.put("timble_error_message", error);
        }
        return e;
    }

    @Test
    void timbleCallbackSucceedsUnlessNameOrAadharIsN() {
        TaskResult ok = run("axis.decomposed.timbleCallback.decide", Map.of("event", ev("Y", "Y", null)));
        assertThat(ok.outputs()).containsEntry("ok", true).containsEntry("status", "SUCCESS")
                .containsEntry("message", "Timble Precheck Successful").containsEntry("customerId", "CUST-1");
        // neither flag present at all: not a failure (AxisTimbleCallbackProcessor.java:55)
        TaskResult none = run("axis.decomposed.timbleCallback.decide", Map.of("event", Map.of("customer_id", "C")));
        assertThat(none.outputs()).containsEntry("ok", true);
    }

    // ------------------------------------------------------------------------------------------ failure notify

    @Test
    void notifyFailProfileKeepsLenderCoresKeyOrderAndOmitsEmptyResponseMapMembers() throws Exception {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", "665f00000000000000000001");
        doc.put("loanRequestId", "LR-1");
        doc.put("customerId", "CUST-1");
        doc.put("precheckStatus", "processed");
        doc.put("lenderSlug", "axis");
        doc.put("approved", false);
        doc.put("applicantId", "APP-1");
        doc.put("clientLoanId", "CLID-1");
        doc.put("reference_id", "TREF-1");
        Map<String, Object> rm = new LinkedHashMap<>();
        rm.put("pan", Map.of("status", "FAILURE", "statusMessage", ""));
        rm.put("axisPreCheckStatus", Map.of("status", "FAILURE", "statusMessage", "m"));
        doc.put("responseMap", rm);

        String profile = (String) run("axis.decomposed.notifyFail.buildProfileJson",
                Map.of("found", true, "doc", doc)).outputs().get("profileJson");

        JsonNode n = JSON.readTree(profile);
        List<String> keys = new ArrayList<>();
        n.fieldNames().forEachRemaining(keys::add);
        // PrecheckEntity.java declaration order, AbstractEntity's id first; client_loan_id per its @JsonProperty
        assertThat(keys).containsExactly("id", "loanRequestId", "customerId", "precheckStatus", "lenderSlug",
                "lenderCustomerId", "rejectionReason", "responseMap", "approved", "retry", "overallstpstatus",
                "unsecurestpstatus", "stpstatus", "lenderstpstatus", "autofundtransferstatus", "applicantId",
                "client_loan_id", "loanProcess", "timbleReferenceId");
        List<String> rmKeys = new ArrayList<>();
        n.get("responseMap").fieldNames().forEachRemaining(rmKeys::add);
        assertThat(rmKeys).containsExactly("axisPreCheckStatus", "pan");   // ResponseMap field order, nulls omitted
        assertThat(n.get("client_loan_id").asText()).isEqualTo("CLID-1");
        assertThat(n.get("timbleReferenceId").asText()).isEqualTo("TREF-1");
        assertThat(run("axis.decomposed.notifyFail.buildProfileJson", Map.of("found", false, "doc", Map.of()))
                .outputs().get("profileJson")).isEqualTo("{}");
    }

    // ------------------------------------------------------------------------------------------ engine bits

    @Test
    void rkCompactIsJacksonNonEmptyAtTheTopLevelOnly() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("a", null);
        in.put("b", "");
        in.put("c", List.of());
        in.put("d", Map.of());
        in.put("e", "x");
        in.put("f", Map.of("keep", (Object) "", "n", "m"));
        assertThat(RkFunctions.compact(in)).containsOnlyKeys("e", "f");
        assertThat(RkFunctions.compact(in).get("f")).isEqualTo(in.get("f"));
        assertThatThrownBy(() -> RkFunctions.compact("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resourceValuesResolveEnvPlaceholders() {
        Resource r = ResourceConfigLoader.load("classpath:flow-config/resources").get("axis-precheck");
        Map<String, String> env = new HashMap<>();
        ClasspathResourceProvider local = new ClasspathResourceProvider(Map.of("axis-precheck", r), "local", env::get);

        assertThatThrownBy(() -> local.values(r)).hasMessageContaining("AXIS_PRECHECK_AGREEMENT_ID");
        env.put("AXIS_PRECHECK_AGREEMENT_ID", "777");
        assertThat(local.values(r)).containsEntry("preCheckAgreementId", "777");

        // qa/prod keep LenderCore's per-profile ids as defaults, overridable
        ClasspathResourceProvider qa = new ClasspathResourceProvider(Map.of("axis-precheck", r), "qa", name -> null);
        assertThat(qa.values(r)).containsEntry("preCheckAgreementId", "2181");
    }

    @Test
    void everyHttpResourceTimesOutBeforeTheActivityDoes() {
        // A longer HTTP read timeout than the activity timeout means Temporal retries a call that is still in
        // flight -- a duplicate POST to Axis.
        ResourceConfigLoader.load("classpath:flow-config/resources").values().stream()
                .filter(r -> r.kind() == Resource.ResourceKind.HTTP)
                .forEach(r -> assertThat(r.timeoutMs().read().longValue())
                        .as("resource %s read timeout", r.resource())
                        .isLessThanOrEqualTo(FlowWorkflowImpl.TASK_START_TO_CLOSE.toMillis()));
    }

    @Test
    void aTaskWithAMissingEnvVarFailsPermanentlyNotSilently() {
        TaskResult ok = run("map.axis.precheckStatusRequest", Map.of("doc", Map.of()));
        assertThat(ok.outputs()).containsEntry("agreement_id", "AGR-1");
        TaskActivityImpl noVar = TestNativeExecutors.build(Map.of(), FlowMongoTestSupport.client(), "lendercore", record -> { });
        TaskDef def = TaskDefLoader.loadFromClasspath("/flow-config/tasks/map.axis.precheckStatusRequest.json");
        assertThatThrownBy(() -> noVar.execute(new TaskRequest(def.kind(), def.taskRef(), 1, "c", "n", 1,
                Map.of("doc", Map.of()), def.fields())))
                .isInstanceOf(ApplicationFailure.class).hasMessageContaining("AXIS_PRECHECK_AGREEMENT_ID");
    }
}
