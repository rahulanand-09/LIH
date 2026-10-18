package com.rupeek.l1.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.rupeek.l1.expr.ExpressionEngine;
import com.rupeek.l1.expr.TemplateEvaluator;
import com.rupeek.l1.ops.OperationRegistry;
import com.rupeek.l1.publish.ConfigBundle;
import com.rupeek.l1.publish.ConfigDirs;
import com.rupeek.l1.publish.GoldenRunner;
import com.rupeek.l1.publish.Publisher;
import com.rupeek.l1.publish.SchemaValidator;
import com.rupeek.l1.publish.parity.ParityReport;
import com.rupeek.l1.publish.parity.ParityRunner;
import com.rupeek.l1.publish.parity.ParityRunner.CaseResult;
import com.rupeek.l1.publish.parity.ParityRunner.Status;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity of the Axis dedupe request: for many customer shapes, the body our config builds must equal, field by
 * field, the body lendercore builds ({@link LegacyAxisPrecheckMapping}). {@code client_loan_id} differs by design
 * (legacy: random UUID per call; L1: stable id reused across reruns), so the legacy side is given ours.
 */
class AxisDedupeParityTest {

    private static final String QA_AGREEMENT_ID = "2181";
    private static ParityRunner parity;

    @BeforeAll
    static void compileCheckedInConfig() {
        List<String> errors = new ArrayList<>();
        Publisher publisher = new Publisher(new InMemoryConfigStore(), true, Clock.systemUTC());
        var compiled = publisher.compile(ConfigBundle.load(ConfigDirs.SOURCE, new SchemaValidator()), "qa", errors);
        assertThat(errors).isEmpty();
        ExpressionEngine expressions = L1Components.standardExpressions();
        OperationRegistry operations = L1Components.standardOperations(new TemplateEvaluator(expressions), Map.of(),
                ref -> "unused");
        parity = new ParityRunner(new GoldenRunner(expressions, operations), compiled.recipe("axis.precheck.dedupe"),
                new InMemoryResourceProvider(compiled.resources(), "qa", n -> "http://parity.invalid"),
                null, Set.of(), "axis.precheck.invoke", "body", Set.of("client_loan_id"));
    }

    // ---------------------------------------------------------------- customers

    private static Map<String, Object> base() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", "64f0c0ffee0000000000c001");
        c.put("firstName", "Asha");
        c.put("lastName", "Verma");
        c.put("customerProofName", "Asha Rani Verma");
        c.put("customerPanName", "ASHA RANI VERMA");
        c.put("customerPhone", "9000000011");
        c.put("email", "asha.test@example.com");
        c.put("gender", "female");
        c.put("dob", "1991-07-21");
        c.put("aadhar", "XXXXXXXX1111");
        c.put("pan", "ABCPV1234K");
        c.put("customerAddress", " 4,  Lake View \t Road,\nPune  ");
        c.put("permanentAddress", new LinkedHashMap<>(Map.of("city", "Pune", "state", "Maharashtra", "pin", "411001")));
        c.put("presentAddress", new LinkedHashMap<>(Map.of("city", "Mumbai", "state", "Maharashtra", "pin", "400001")));
        c.put("leadId", "ignored-by-both");
        return c;
    }

    private static Map<String, Object> with(Consumer<Map<String, Object>> change) {
        Map<String, Object> c = base();
        change.accept(c);
        return c;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> address(Map<String, Object> c, String which) {
        return (Map<String, Object>) c.get(which);
    }

    /** Customer shapes where lendercore produces a request. */
    static Map<String, Map<String, Object>> customers() {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        m.put("three-part name", base());
        m.put("two-part name", with(c -> c.put("customerProofName", "Kiran Joshi")));
        m.put("one-part name", with(c -> c.put("customerProofName", "Madonna")));
        m.put("five-part name", with(c -> c.put("customerProofName", "a b c d e")));
        m.put("double space in name", with(c -> c.put("customerProofName", "Ravi  Kumar")));
        m.put("leading space in name", with(c -> c.put("customerProofName", " Ravi Kumar")));
        m.put("trailing space in name", with(c -> c.put("customerProofName", "Ravi Kumar ")));
        m.put("non-ascii name", with(c -> c.put("customerProofName", "Ñandú Pérez")));
        m.put("empty proof name", with(c -> c.put("customerProofName", "")));
        m.put("no proof name", with(c -> c.remove("customerProofName")));
        m.put("null proof name", with(c -> c.put("customerProofName", null)));
        m.put("numeric phone", with(c -> c.put("customerPhone", 9000000011L)));
        m.put("numeric pins", with(c -> {
            address(c, "permanentAddress").put("pin", 411001);
            address(c, "presentAddress").put("pin", 400001L);
        }));
        m.put("no present address", with(c -> c.remove("presentAddress")));
        m.put("partial present address", with(c -> c.put("presentAddress", new LinkedHashMap<>(Map.of("city", "Thane")))));
        m.put("missing optional fields", with(c -> {
            c.remove("email");
            c.remove("gender");
            c.remove("customerPanName");
            c.remove("aadhar");
        }));
        m.put("explicit nulls", with(c -> {
            c.put("email", null);
            c.put("dob", null);
            c.put("pan", null);
        }));
        m.put("male", with(c -> c.put("gender", "male")));
        return m;
    }

    // ---------------------------------------------------------------- tests

    @Test
    void ourRequestEqualsLegacyForEveryCustomerShape() {
        List<CaseResult> results = new ArrayList<>();
        customers().forEach((name, customer) -> results.add(compare(name, customer)));
        assertThat(results).as(ParityReport.text(results, true)).allMatch(r -> r.status() == Status.MATCH);
    }

    @Test
    void whereLegacyCrashesWeFailTooAndNeverCallAxis() {
        Map<String, Map<String, Object>> broken = new LinkedHashMap<>();
        broken.put("no permanent address", with(c -> c.remove("permanentAddress")));
        broken.put("no customer address", with(c -> c.remove("customerAddress")));
        broken.put("blank proof name", with(c -> c.put("customerProofName", "   ")));
        broken.forEach((name, customer) -> {
            boolean legacyThrows;
            try {
                LegacyAxisPrecheckMapping.request(customer, QA_AGREEMENT_ID, "x");
                legacyThrows = false;
            } catch (RuntimeException e) {
                legacyThrows = true;
            }
            assertThat(legacyThrows).as(name + ": legacy").isTrue();
            CaseResult ours = parity.check(name, input(), coreMock(customer), null);
            assertThat(ours.status()).as(name + ": ours").isEqualTo(Status.ERROR);
            assertThat(ours.message()).contains("PERMANENT");
        });
    }

    @Test
    void aRealDifferenceIsReported() {
        Map<String, Object> customer = base();
        JsonNode legacy = LegacyAxisPrecheckMapping.request(customer, QA_AGREEMENT_ID, "x");
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).put("gender", "F");
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).remove("application_id");
        CaseResult r = parity.check("tampered", input(), coreMock(customer), legacy);
        assertThat(r.status()).isEqualTo(Status.DIFF);
        assertThat(r.diffs()).extracting(d -> d.path()).containsExactlyInAnyOrder("gender", "application_id");
        assertThat(ParityReport.text(List.of(r), false)).contains("gender: legacy=\"*\" ours=\"XXmale\"")
                .contains("application_id: legacy=<absent> ours=null");
    }

    private static CaseResult compare(String name, Map<String, Object> customer) {
        JsonNode legacy = LegacyAxisPrecheckMapping.request(customer, QA_AGREEMENT_ID, "ignored");
        return parity.check(name, input(), coreMock(customer), legacy);
    }

    private static Map<String, Object> input() {
        return Map.of("loanRequestId", "LR-PARITY-1", "runNo", 1);
    }

    private static Map<String, Map<String, Object>> coreMock(Map<String, Object> customer) {
        Map<String, Object> loan = new HashMap<>();
        loan.put("customer", customer);
        return Map.of("core.fetchCustomerByLoanRequest@1",
                Map.of("resp", Map.of("status", 200, "body", Map.of("loandata", List.of(loan)))));
    }
}
