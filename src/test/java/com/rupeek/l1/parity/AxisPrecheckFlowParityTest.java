package com.rupeek.l1.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rupeek.l1.expr.ExpressionEngine;
import com.rupeek.l1.expr.TemplateEvaluator;
import com.rupeek.l1.ops.OperationRegistry;
import com.rupeek.l1.parity.LegacyAxisPrecheckFlow.PrecheckEntity;
import com.rupeek.l1.parity.LegacyAxisPrecheckFlow.ResponseMap;
import com.rupeek.l1.parity.LegacyAxisPrecheckFlow.ResponseMapStatus;
import com.rupeek.l1.parity.LegacyAxisPrecheckFlow.StatusDecision;
import com.rupeek.l1.parity.LegacyAxisPrecheckFlow.TimbleResult;
import com.rupeek.l1.publish.ConfigBundle;
import com.rupeek.l1.publish.ConfigDirs;
import com.rupeek.l1.publish.GoldenCase;
import com.rupeek.l1.publish.GoldenRunner;
import com.rupeek.l1.publish.GoldenRunner.Call;
import com.rupeek.l1.publish.GoldenRunner.Run;
import com.rupeek.l1.publish.Publisher;
import com.rupeek.l1.publish.SchemaValidator;
import com.rupeek.l1.publish.parity.JsonDiff;
import com.rupeek.l1.registry.InMemoryConfigStore;
import com.rupeek.l1.registry.InMemoryResourceProvider;
import com.rupeek.l1.worker.L1Components;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity of the rest of the Axis precheck with lendercore ({@link LegacyAxisPrecheckFlow} as oracle): the status
 * decision for every combination of flags and Timble results, the status request, the Timble request, the Timble
 * callback result and message, and the profile sent to rupeekweb on failure.
 */
class AxisPrecheckFlowParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LR = "LR-P-1";
    private static Publisher.Compiled compiled;
    private static GoldenRunner golden;
    private static InMemoryResourceProvider resources;

    @BeforeAll
    static void compile() {
        List<String> errors = new ArrayList<>();
        compiled = new Publisher(new InMemoryConfigStore(), true, Clock.systemUTC())
                .compile(ConfigBundle.load(ConfigDirs.SOURCE, new SchemaValidator()), "qa", errors);
        assertThat(errors).isEmpty();
        ExpressionEngine expressions = L1Components.standardExpressions();
        OperationRegistry operations = L1Components.standardOperations(new TemplateEvaluator(expressions), Map.of(), r -> "x");
        golden = new GoldenRunner(expressions, operations);
        resources = new InMemoryResourceProvider(compiled.resources(), "qa", n -> "http://parity.invalid");
    }

    private static Run run(String recipe, Map<String, Object> input, Map<String, Map<String, Object>> mocks) {
        GoldenCase c = new GoldenCase("parity", recipe, input, null, null, mocks,
                new GoldenCase.Expect("ANY", null, null, null, null));
        return golden.execute(c, compiled.recipe(recipe), resources, null, Set.of());
    }

    private static Map<String, Object> params(Run run, String op) {
        Call call = run.calls().stream().filter(c -> c.op().equals(op)).findFirst().orElse(null);
        return call == null ? null : call.params();
    }

    private static Map<String, Object> mock(Object result) {
        Map<String, Object> m = new HashMap<>();
        m.put("result", result);
        return m;
    }

    private static Map<String, Object> resp(int status, Object body) {
        Map<String, Object> r = new HashMap<>();
        r.put("status", status);
        r.put("body", body);
        return Map.of("resp", r);
    }

    private static Map<String, Object> asMap(Object pojo) {
        return JSON.convertValue(pojo, Map.class);
    }

    // ---------------------------------------------------------------- status decision

    @Test
    void statusDecisionMatchesLegacyForEveryCombination() throws Exception {
        List<String> values = List.of("Y", "WIP", "N");
        List<ResponseMapStatus> timbles = new ArrayList<>();
        timbles.add(null);
        timbles.add(new ResponseMapStatus("SUCCESS", "Timble Precheck Successful"));
        timbles.add(new ResponseMapStatus("success", "lower case"));
        timbles.add(new ResponseMapStatus("FAILURE", "Timble Precheck Failure : {}"));
        timbles.add(new ResponseMapStatus(null, null));
        int checked = 0;
        for (String d : values) for (String p : values) for (String r : values) for (String h : values) {
            for (ResponseMapStatus t : timbles) {
                Map<String, String> flags = Map.of("dedupe", d, "pan", p, "ramp", r, "hunter", h);
                StatusDecision legacy = LegacyAxisPrecheckFlow.decide(flags, t);

                Map<String, Object> responseMap = new LinkedHashMap<>();
                if (t != null) {
                    responseMap.put("timble", asMap(t));
                }
                Map<String, Object> doc = new LinkedHashMap<>(Map.of("loanRequestId", LR, "customerId", "C1",
                        "applicantId", "A1", "clientLoanId", "CL1"));
                doc.put("responseMap", responseMap);
                Run ours = run("axis.precheck.status", Map.of("loanRequestId", LR), Map.of(
                        "precheck.findByLoanRequest@1", mock(doc),
                        "axis.precheck.status@1", resp(200, flags)));

                String where = flags + " timble=" + (t == null ? null : t.status);
                assertThat(ours.result()).as(where).isNotNull();
                assertThat(ours.result().outcome()).as(where).isEqualTo(legacy.outcome());
                boolean pending = legacy.outcome().equals("PENDING");
                Map<String, Object> saved = params(ours, pending ? "axis.precheck.saveStatusPending@1" : "axis.precheck.saveStatusFinal@1");
                assertThat(saved).as(where).isNotNull();
                assertThat((JsonNode) JSON.valueToTree(saved.get("overall"))).as(where + " overall")
                        .isEqualTo((JsonNode) JSON.valueToTree(legacy.responseMap().axisPreCheckStatus));
                for (String f : List.of("dedupe", "pan", "ramp", "hunter")) {
                    assertThat((JsonNode) JSON.valueToTree(saved.get(f))).as(where + " " + f)
                            .isEqualTo((JsonNode) JSON.valueToTree(asMap(legacy.responseMap()).get(f)));
                }
                if (!pending) {
                    assertThat(saved.get("approved")).as(where + " approved").isEqualTo(legacy.approved());
                }
                checked++;
            }
        }
        assertThat(checked).isEqualTo(81 * 5);
    }

    @Test
    void anUnknownFlagFailsLikeLegacy() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                LegacyAxisPrecheckFlow.decide(Map.of("dedupe", "X", "pan", "Y", "ramp", "Y", "hunter", "Y"), null));
        Run ours = run("axis.precheck.status", Map.of("loanRequestId", LR), Map.of(
                "precheck.findByLoanRequest@1", mock(Map.of("loanRequestId", LR, "responseMap", Map.of())),
                "axis.precheck.status@1", resp(200, Map.of("dedupe", "X", "pan", "Y", "ramp", "Y", "hunter", "Y"))));
        assertThat(ours.failure()).isEqualTo("L1_PERMANENT");
    }

    @Test
    void statusRequestMatchesLegacy() {
        Run ours = run("axis.precheck.status", Map.of("loanRequestId", LR), Map.of(
                "precheck.findByLoanRequest@1", mock(Map.of("loanRequestId", LR, "customerId", "C1", "applicantId", "A1",
                        "clientLoanId", "CL1", "responseMap", Map.of())),
                "axis.precheck.status@1", resp(200, Map.of("dedupe", "Y", "pan", "Y", "ramp", "Y", "hunter", "WIP"))));
        JsonNode legacy = LegacyAxisPrecheckFlow.statusRequest("A1", "CL1", "C1", "2181");
        assertThat(JsonDiff.diff(legacy, JSON.valueToTree(params(ours, "axis.precheck.status@1").get("body")), Set.of()))
                .isEmpty();
    }

    // ---------------------------------------------------------------- Timble request

    @Test
    void timbleSendsExactlyTheDedupeRequest() throws Exception {
        Map<String, Object> core = JSON.readValue(getClass().getResourceAsStream("/l1-fixtures/axis/core-customer-response.json"), Map.class);
        Map<String, Object> input = Map.of("loanRequestId", LR, "runNo", 1, "loanProcess", "fresh");
        Run dedupe = run("axis.precheck.dedupe", input, Map.of("core.fetchCustomerByLoanRequest@1", resp(200, core),
                "axis.precheck.invoke@1", resp(200, Map.of("data", Map.of("applicantId", "A1")))));
        Run timble = run("axis.precheck.timble", input, Map.of("core.fetchCustomerByLoanRequest@1", resp(200, core),
                "axis.precheck.timble@1", resp(200, Map.of("reference_id", "T1"))));
        Object dedupeBody = params(dedupe, "axis.precheck.invoke@1").get("body");
        Object timbleBody = params(timble, "axis.precheck.timble@1").get("body");
        assertThat(JsonDiff.diff(JSON.valueToTree(dedupeBody), JSON.valueToTree(timbleBody), Set.of())).isEmpty();
    }

    // ---------------------------------------------------------------- Timble callback

    @Test
    void timbleCallbackMatchesLegacy() throws Exception {
        List<Map<String, Object>> events = new ArrayList<>();
        events.add(event("Y", "Y", null));
        events.add(event("N", "Y", "Name mismatch"));
        events.add(event("Y", "N", null));
        events.add(event(" n ", "Y", null));
        events.add(event("n", "N", "both"));
        events.add(event(null, "Y", null));
        events.add(event("Y", null, "only message"));
        events.add(event("YES", "maybe", null));
        Map<String, Object> numeric = event("Y", "Y", null);
        numeric.put("name_match", 0);
        events.add(numeric);
        Map<String, Object> extra = event("N", "Y", null);
        extra.put("unrelated", "ignored");
        events.add(extra);

        for (Map<String, Object> e : events) {
            TimbleResult legacy = LegacyAxisPrecheckFlow.timbleCallback(e);
            Run ours = run("axis.precheck.timbleCallback", Map.of("event", e), Map.of(
                    "precheck.findLatestByCustomer@1", mock(Map.of("loanRequestId", LR, "customerId", "C1"))));
            assertThat(ours.result().outcome()).as(e.toString()).isEqualTo(legacy.ok() ? "TIMBLE_OK" : "TIMBLE_FAILED");
            Map<String, Object> saved = params(ours, "axis.precheck.saveTimbleStatus@1");
            assertThat(saved.get("status")).as(e.toString()).isEqualTo(legacy.status());
            assertThat(saved.get("message")).as(e.toString()).isEqualTo(legacy.message());
        }
    }

    private static Map<String, Object> event(String name, String aadhar, String error) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("product_id", "P1");
        e.put("customer_id", "C1");
        e.put("timble_status", "COMPLETED");
        if (name != null) {
            e.put("name_match", name);
        }
        if (aadhar != null) {
            e.put("aadhar_match", aadhar);
        }
        if (error != null) {
            e.put("timble_error_message", error);
        }
        return e;
    }

    // ---------------------------------------------------------------- failure notification

    @Test
    void failureProfileMatchesLegacy() throws Exception {
        Map<String, Object> responseMap = new LinkedHashMap<>();
        responseMap.put("axisPreCheckStatus", Map.of("status", "FAILURE", "statusMessage", "Precheck failure received ..."));
        responseMap.put("pan", Map.of("status", "FAILURE", "statusMessage", ""));
        responseMap.put("timble", Map.of("status", "SUCCESS", "statusMessage", "Timble Precheck Successful"));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", "64f0c0ffee0000000000c999");
        doc.put("loanRequestId", LR);
        doc.put("customerId", "C1");
        doc.put("precheckStatus", "processed");
        doc.put("lenderSlug", "axis");
        doc.put("responseMap", responseMap);
        doc.put("approved", false);
        doc.put("applicantId", "A1");
        doc.put("clientLoanId", "CL1");
        doc.put("reference_id", "T1");
        doc.put("version", 4);
        doc.put("creation_date", "2026-10-13T12:28:00Z");
        doc.put("_class", "com.rupeek.lender.entity.PrecheckEntity");

        PrecheckEntity e = new PrecheckEntity();
        e.id = "64f0c0ffee0000000000c999";
        e.loanRequestId = LR;
        e.customerId = "C1";
        e.precheckStatus = "processed";
        e.lenderSlug = "axis";
        e.approved = false;
        e.applicantId = "A1";
        e.clientLoanId = "CL1";
        e.timbleReferenceId = "T1";
        e.responseMap = new ResponseMap();
        e.responseMap.axisPreCheckStatus = new ResponseMapStatus("FAILURE", "Precheck failure received ...");
        e.responseMap.pan = new ResponseMapStatus("FAILURE", "");
        e.responseMap.timble = new ResponseMapStatus("SUCCESS", "Timble Precheck Successful");
        JsonNode legacy = JSON.readTree(LegacyAxisPrecheckFlow.lenderCustomerProfile(e));

        Run ours = run("precheck.notify.failed", Map.of("loanRequestId", LR, "lender", "axis"), Map.of(
                "precheck.findByLoanRequest@1", mock(doc), "rupeekweb.precheck.failure@1", resp(200, Map.of())));
        Map<String, Object> sent = params(ours, "rupeekweb.precheck.failure@1");
        assertThat(sent.get("lender")).isEqualTo("axis");
        assertThat(JsonDiff.diff(legacy, JSON.readTree((String) sent.get("profileJson")), Set.of())).isEmpty();
    }
}
