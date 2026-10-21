package com.rupeek.l1.expr;

import com.rupeek.l1.expr.fn.RkFunctions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateEvaluatorTest {

    private final ExpressionEngine engine = new CelExpressionEngine(ExprLimits.DEFAULT, List.of(new RkFunctions()));
    private final TemplateEvaluator templates = new TemplateEvaluator(engine);

    private Object render(Object template, Map<String, Object> params) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("params", params);
        vars.put("env", Map.of("agreementId", "2181"));
        vars.put("sys", Map.of());
        return templates.render(templates.compile(template, ScopeDecl.OPERATION, "op.template"), vars);
    }

    @Test
    void literalsStayLiteralAndExpressionsKeepTheirType() {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("constant", "CLM1");
        template.put("number", 7);
        template.put("flag", true);
        template.put("nothing", null);
        template.put("agreement", "=env.agreementId");
        template.put("count", "=params.n + 1");
        template.put("escaped", "==not an expression");

        Object out = render(template, Map.of("n", 2));

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("constant", "CLM1");
        expected.put("number", 7);
        expected.put("flag", true);
        expected.put("nothing", null);
        expected.put("agreement", "2181");
        expected.put("count", 3L);
        expected.put("escaped", "=not an expression");
        assertThat(out).isEqualTo(expected);
    }

    @Test
    void nestedListsAndNullResultsAreKept() {
        Map<String, Object> params = new HashMap<>();
        params.put("pan", "ABCDE1234F");
        params.put("aadhar", null);
        Object out = render(Map.of("ids", List.of(
                Map.of("type", "PAN", "value", "=params.pan"),
                Map.of("type", "AADHAR", "value", "=params.aadhar"))), params);

        Map<String, Object> aadhar = new HashMap<>();
        aadhar.put("type", "AADHAR");
        aadhar.put("value", null);
        assertThat(out).isEqualTo(Map.of("ids", List.of(Map.of("type", "PAN", "value", "ABCDE1234F"), aadhar)));
    }

    @Test
    void eachRendersOncePerItemWithLetValuesInOrder() {
        Map<String, Object> each = new LinkedHashMap<>();
        each.put("$each", "params.items");
        each.put("as", "it");
        Map<String, Object> lets = new LinkedHashMap<>();
        lets.put("net", "it.gross - it.stone");
        lets.put("netRounded", "rk.round(net, 1, 'HALF_UP')");
        each.put("$let", lets);
        each.put("template", Map.of("id", "=it.id", "net", "=netRounded"));

        Object out = render(Map.of("jewels", each), Map.of("items", List.of(
                Map.of("id", "J1", "gross", 10.26, "stone", 0.2),
                Map.of("id", "J2", "gross", 5.0, "stone", 0.0))));

        assertThat(out).isEqualTo(Map.of("jewels", List.of(
                Map.of("id", "J1", "net", 10.1),
                Map.of("id", "J2", "net", 5.0))));
    }

    @Test
    void eachOverNullRendersEmptyList() {
        Map<String, Object> params = new HashMap<>();
        params.put("items", null);
        Object out = render(Map.of("x", Map.of("$each", "params.items", "as", "it", "template", "=it")), params);
        assertThat(out).isEqualTo(Map.of("x", List.of()));
    }

    @Test
    void eachVariablesAreOnlyInScopeInsideTheBlock() {
        assertThatThrownBy(() -> templates.compile(
                Map.of("a", Map.of("$each", "params.items", "as", "it", "template", "=it"), "b", "=it"),
                ScopeDecl.OPERATION, "op.template"))
                .isInstanceOf(ExprCompileException.class).hasMessageContaining("op.template.b");
    }

    @Test
    void unknownDirectivesAndMalformedEachFailAtCompileTime() {
        assertThatThrownBy(() -> templates.compile(Map.of("$foreach", "x"), ScopeDecl.OPERATION, "t"))
                .isInstanceOf(ExprCompileException.class).hasMessageContaining("unknown template directive");
        assertThatThrownBy(() -> templates.compile(Map.of("$each", "params.items", "template", "=1"), ScopeDecl.OPERATION, "t"))
                .isInstanceOf(ExprCompileException.class).hasMessageContaining("'as'");
        assertThatThrownBy(() -> templates.compile(
                Map.of("$each", "params.items", "as", "it", "template", "=1", "extra", 1), ScopeDecl.OPERATION, "t"))
                .isInstanceOf(ExprCompileException.class).hasMessageContaining("not allowed");
    }

    @Test
    void compileErrorsCarryTheTemplatePath() {
        assertThatThrownBy(() -> templates.compile(Map.of("list", Arrays.asList("ok", "=params.")), ScopeDecl.OPERATION, "op"))
                .isInstanceOf(ExprCompileException.class).hasMessageContaining("op.list[1]");
    }
}
