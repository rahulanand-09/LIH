package com.rupeek.l1.axis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rupeek.l1.expr.CelExpressionEngine;
import com.rupeek.l1.expr.CompiledExpr;
import com.rupeek.l1.expr.ExprLimits;
import com.rupeek.l1.expr.ExpressionEngine;
import com.rupeek.l1.expr.ScopeDecl;
import com.rupeek.l1.expr.TemplateEvaluator;
import com.rupeek.l1.expr.fn.RkFunctions;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.OperationKind;
import com.rupeek.l1.model.Recipe;
import com.rupeek.l1.model.Step;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 1 exit check: every expression in the Axis Dedupe config compiles in its scope, and the key ones
 * evaluate correctly against recorded (synthetic) Core and Axis responses.
 */
class AxisDedupeExpressionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExpressionEngine ENGINE = new CelExpressionEngine(ExprLimits.DEFAULT, List.of(new RkFunctions()));
    private static final TemplateEvaluator TEMPLATES = new TemplateEvaluator(ENGINE);

    private static ClasspathConfig config;
    private static Recipe recipe;

    @BeforeAll
    static void load() throws IOException {
        config = ClasspathConfig.load();
        recipe = config.recipes.get("axis.precheck.dedupe@1");
        assertThat(recipe).isNotNull();
    }

    // ------------------------------------------------------------------ everything compiles

    @Test
    void everyExpressionInTheAxisDedupeConfigCompiles() {
        List<String> compiled = new ArrayList<>();
        String r = recipe.recipe();
        recipe.auditKeys().forEach((k, src) -> compiled.add(ENGINE.compile(src, ScopeDecl.STEP, r + "/auditKeys." + k).configPath()));
        for (int i = 0; i < recipe.steps().size(); i++) {
            Step s = recipe.steps().get(i);
            String p = r + "/steps[" + i + "]";
            if (s.when() != null) {
                compiled.add(ENGINE.compile(s.when(), ScopeDecl.STEP, p + ".when").configPath());
            }
            s.in().forEach((k, src) -> compiled.add(ENGINE.compile(src, ScopeDecl.STEP, p + ".in." + k).configPath()));
            if (s.isReturn()) {
                s.returnSpec().out().forEach((k, src) -> compiled.add(ENGINE.compile(src, ScopeDecl.STEP, p + ".return." + k).configPath()));
            } else {
                assertThat(config.op(s.op())).as("operation %s referenced by %s", s.op(), p).isNotNull();
            }
        }
        recipe.returnSpec().out().forEach((k, src) -> compiled.add(ENGINE.compile(src, ScopeDecl.STEP, r + "/return." + k).configPath()));

        for (OperationDef op : config.operations.values()) {
            String p = op.op();
            ScopeDecl outScope = op.kind() == OperationKind.HTTP ? ScopeDecl.HTTP_RESPONSE : ScopeDecl.RESULT;
            op.out().forEach((k, src) -> compiled.add(ENGINE.compile(src, outScope, p + "/out." + k).configPath()));
            if (op.kind() == OperationKind.HTTP) {
                compiled.add(ENGINE.compile(op.success(), ScopeDecl.HTTP_RESPONSE, p + "/success").configPath());
                compiled.add(ENGINE.compile(op.classify(), ScopeDecl.HTTP_RESPONSE, p + "/classify").configPath());
                TEMPLATES.compile(op.request().path(), ScopeDecl.OPERATION, p + "/request.path");
                TEMPLATES.compile(op.request().query(), ScopeDecl.OPERATION, p + "/request.query");
                TEMPLATES.compile(op.request().headers(), ScopeDecl.OPERATION, p + "/request.headers");
                TEMPLATES.compile(op.request().body(), ScopeDecl.OPERATION, p + "/request.body");
            }
            if (op.kind() == OperationKind.MAP) {
                TEMPLATES.compile(op.template(), ScopeDecl.OPERATION, p + "/template");
            }
            if (op.audit() != null && op.audit().startsWith("=")) {
                compiled.add(ENGINE.compile(op.audit().substring(1), ScopeDecl.OPERATION, p + "/audit").configPath());
            }
        }
        assertThat(compiled).hasSizeGreaterThan(25);
    }

    // ------------------------------------------------------------------ operations against recorded responses

    @Test
    void coreFetchSucceedsAndExtractsTheCustomer() throws IOException {
        OperationDef core = config.op("core.fetchCustomerByLoanRequest@1");
        Map<String, Object> scope = httpScope(Map.of("loanRequestId", "LR-1"), 200, fixture("core-customer-response.json"));

        assertThat(ENGINE.evalBool(compile(core.success(), ScopeDecl.HTTP_RESPONSE), scope)).isTrue();
        Object customer = ENGINE.eval(compile(core.out().get("customer"), ScopeDecl.HTTP_RESPONSE), scope);
        assertThat(customer).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("id", "CUST-TEST-1");
    }

    @Test
    void coreFetchWithEmptyLoanDataIsPermanent() {
        OperationDef core = config.op("core.fetchCustomerByLoanRequest@1");
        Map<String, Object> scope = httpScope(Map.of(), 200, Map.of("loandata", List.of()));
        assertThat(ENGINE.evalBool(compile(core.success(), ScopeDecl.HTTP_RESPONSE), scope)).isFalse();
        assertThat(ENGINE.eval(compile(core.classify(), ScopeDecl.HTTP_RESPONSE), scope)).isEqualTo("PERMANENT");
    }

    @Test
    void axisRequestBodyMatchesTheLegacyMapping() throws IOException {
        OperationDef map = config.op("map.axis.precheckRequest@1");
        Map<String, Object> customer = customer();
        Map<String, Object> vars = new HashMap<>();
        vars.put("params", Map.of("customer", customer, "clientLoanId", "CLID-1"));
        vars.put("env", config.resources.get("axis-precheck").valuesFor("qa"));
        vars.put("sys", Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) TEMPLATES.render(
                TEMPLATES.compile(map.template(), ScopeDecl.OPERATION, "map/template"), vars);

        assertThat(body)
                .containsEntry("agreement_id", "2181")
                .containsEntry("customer_id", "CUST-TEST-1")
                .containsEntry("client_loan_id", "CLID-1")
                .containsEntry("model_name", "CLM1")
                .containsEntry("customer_type", "individual")
                .containsEntry("first_name", "RAVI")
                .containsEntry("middle_name", "KUMAR")
                .containsEntry("last_name", "SHARMA")
                .containsEntry("date_of_birth", "1990-01-05")
                .containsEntry("dedupe_type", "single");
        assertThat(body.get("dedupe_ids")).isEqualTo(List.of(
                Map.of("id_type", "PAN", "id_value", "ABCDE1234F"),
                Map.of("id_type", "AADHAR", "id_value", "000011112222")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> address = (List<Map<String, Object>>) body.get("address");
        assertThat(address.get(0)).containsEntry("address_value", "12, MG Road Bengaluru")
                .containsEntry("address_city", "Bengaluru");
        assertThat(address.get(1)).containsEntry("address_type", "COMMUNICATION").containsEntry("address_city", "Mysuru");
    }

    @Test
    void axisInvokeSuccessAndBusinessFailure() throws IOException {
        OperationDef invoke = config.op("axis.precheck.invoke@1");
        CompiledExpr success = compile(invoke.success(), ScopeDecl.HTTP_RESPONSE);
        CompiledExpr classify = compile(invoke.classify(), ScopeDecl.HTTP_RESPONSE);
        CompiledExpr applicantId = compile(invoke.out().get("applicantId"), ScopeDecl.HTTP_RESPONSE);

        Map<String, Object> ok = httpScope(Map.of("type", "dedupe"), 200, fixture("axis-dedupe-success.json"));
        assertThat(ENGINE.evalBool(success, ok)).isTrue();
        assertThat(ENGINE.eval(applicantId, ok)).isEqualTo("APP-TEST-9");

        Map<String, Object> errorsIn200 = httpScope(Map.of("type", "dedupe"), 200, fixture("axis-dedupe-errors-in-200.json"));
        assertThat(ENGINE.evalBool(success, errorsIn200)).isFalse();
        assertThat(ENGINE.eval(classify, errorsIn200)).isEqualTo("BUSINESS");
        assertThat(ENGINE.eval(applicantId, errorsIn200)).isNull();

        Map<String, Object> serverError = httpScope(Map.of("type", "dedupe"), 500, Map.of());
        assertThat(ENGINE.eval(classify, serverError)).isEqualTo("UNKNOWN");   // may have been processed (Q5)

        Map<String, Object> unavailable = httpScope(Map.of("type", "dedupe"), 503, Map.of());
        assertThat(ENGINE.evalBool(success, unavailable)).isFalse();
        assertThat(ENGINE.eval(classify, unavailable)).isEqualTo("TECHNICAL");
    }

    @Test
    void auditTypeFollowsTheCallType() {
        OperationDef invoke = config.op("axis.precheck.invoke@1");
        CompiledExpr audit = ENGINE.compile(invoke.audit().substring(1), ScopeDecl.OPERATION, "audit");
        assertThat(ENGINE.eval(audit, Map.of("params", Map.of("type", "dedupe")))).isEqualTo("PRECHECK_DEDUPE");
        assertThat(ENGINE.eval(audit, Map.of("params", Map.of("type", "dedupe_timble")))).isEqualTo("PRECHECK_TIMBLE");
    }

    // ------------------------------------------------------------------ recipe expressions

    @Test
    void alreadyDoneGuardOnlyFiresForACompletedRecord() {
        CompiledExpr when = compile(step("alreadyDone").when(), ScopeDecl.STEP);

        Map<String, Object> completed = new HashMap<>();
        completed.put("applicantId", "APP-1");
        completed.put("client_loan_id", "CLID-1");
        assertThat(ENGINE.evalBool(when, stepScope(Map.of("existing", existing(completed))))).isTrue();

        Map<String, Object> half = new HashMap<>();          // dedupe failed or crashed earlier
        half.put("applicantId", null);
        half.put("client_loan_id", "CLID-1");
        assertThat(ENGINE.evalBool(when, stepScope(Map.of("existing", existing(half))))).isFalse();

        Map<String, Object> notFound = new HashMap<>();
        notFound.put("found", false);
        notFound.put("doc", null);
        assertThat(ENGINE.evalBool(when, stepScope(Map.of("existing", notFound)))).isFalse();
    }

    @Test
    void idsStepReusesTheStoredClientLoanIdAndBuildsAStableSeed() {
        Step ids = step("ids");
        CompiledExpr reuse = compile(ids.in().get("reuse"), ScopeDecl.STEP);
        CompiledExpr seed = compile(ids.in().get("seed"), ScopeDecl.STEP);

        Map<String, Object> half = new HashMap<>();
        half.put("client_loan_id", "CLID-OLD");
        assertThat(ENGINE.eval(reuse, stepScope(Map.of("existing", existing(half))))).isEqualTo("CLID-OLD");

        Map<String, Object> notFound = new HashMap<>();
        notFound.put("found", false);
        notFound.put("doc", null);
        assertThat(ENGINE.eval(reuse, stepScope(Map.of("existing", notFound)))).isEqualTo("");
        assertThat(ENGINE.eval(seed, stepScope(Map.of()))).isEqualTo("LR-1:r1:axis-dedupe");
    }

    @Test
    void auditKeysAndReturnOutputs() {
        Map<String, Object> steps = Map.of(
                "core", Map.of("customer", Map.of("id", "CUST-TEST-1")),
                "call", Map.of("applicantId", "APP-9"),
                "ids", Map.of("value", "CLID-1"));
        Map<String, Object> scope = stepScope(steps);
        assertThat(ENGINE.eval(compile(recipe.auditKeys().get("customerId"), ScopeDecl.STEP), scope)).isEqualTo("CUST-TEST-1");
        assertThat(ENGINE.eval(compile(recipe.auditKeys().get("customerId"), ScopeDecl.STEP), stepScope(Map.of()))).isNull();
        assertThat(ENGINE.eval(compile(recipe.returnSpec().out().get("applicantId"), ScopeDecl.STEP), scope)).isEqualTo("APP-9");
        assertThat(recipe.outcomes()).containsExactly("INITIATED", "ALREADY_INITIATED", "REJECTED");
    }

    // ------------------------------------------------------------------ helpers

    private static CompiledExpr compile(String source, ScopeDecl scope) {
        return ENGINE.compile(source, scope, "test");
    }

    private static Step step(String id) {
        return recipe.steps().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    private static Map<String, Object> existing(Map<String, Object> doc) {
        Map<String, Object> out = new HashMap<>();
        out.put("found", true);
        out.put("doc", doc);
        return out;
    }

    private static Map<String, Object> stepScope(Map<String, Object> steps) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("input", Map.of("loanRequestId", "LR-1", "runNo", 1));
        vars.put("steps", steps);
        vars.put("env", Map.of());
        vars.put("sys", Map.of());
        return vars;
    }

    private static Map<String, Object> httpScope(Map<String, Object> params, int status, Object body) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("params", params);
        vars.put("resp", Map.of("status", status, "headers", Map.of(), "body", body));
        vars.put("env", Map.of());
        return vars;
    }

    private static Map<String, Object> customer() throws IOException {
        Map<String, Object> core = fixture("core-customer-response.json");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> loanData = (List<Map<String, Object>>) core.get("loandata");
        @SuppressWarnings("unchecked")
        Map<String, Object> customer = (Map<String, Object>) loanData.get(0).get("customer");
        return customer;
    }

    private static Map<String, Object> fixture(String name) throws IOException {
        try (InputStream in = AxisDedupeExpressionsTest.class.getResourceAsStream("/l1-fixtures/axis/" + name)) {
            return JSON.readValue(in, new TypeReference<>() { });
        }
    }
}
