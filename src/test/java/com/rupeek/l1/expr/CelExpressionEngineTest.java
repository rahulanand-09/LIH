package com.rupeek.l1.expr;

import com.rupeek.l1.expr.fn.RkFunctions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the CEL behaviours L1 relies on (dev.cel 0.14.0). If a library upgrade changes one, this fails first.
 */
class CelExpressionEngineTest {

    private final ExpressionEngine engine = new CelExpressionEngine(ExprLimits.DEFAULT, List.of(new RkFunctions()));

    private Object eval(String source, Map<String, Object> input) {
        CompiledExpr expr = engine.compile(source, ScopeDecl.STEP, "test");
        Map<String, Object> vars = new HashMap<>();
        vars.put("input", input);
        return engine.eval(expr, vars);
    }

    private static Map<String, Object> input() {
        Map<String, Object> m = new HashMap<>();
        m.put("int", 5);                       // Integer, as Jackson produces
        m.put("long", 7L);
        m.put("dbl", 2.5);
        m.put("nul", null);                    // a present key with a null value
        m.put("name", "  RAVI   KUMAR ");
        m.put("customer", Map.of("id", "C1"));
        m.put("items", List.of(1, 2, 3));
        return m;
    }

    @Test
    void javaNullsAndIntegersAreNormalisedOnTheWayIn() {
        assertThat(eval("input.int + 1", input())).isEqualTo(6L);
        assertThat(eval("input.nul == null", input())).isEqualTo(true);
        assertThat(eval("input.items.map(v, v * 2)", input())).isEqualTo(List.of(2L, 4L, 6L));
    }

    @Test
    void celNullComesBackAsJavaNull() {
        assertThat(eval("input.nul", input())).isNull();
        Object map = eval("{'a': 1, 'b': null}", input());
        assertThat(map).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) map;
        assertThat(result).containsEntry("a", 1L).containsEntry("b", null);
    }

    @Test
    void orValueOnlyDefaultsWhenTheKeyIsMissing() {
        assertThat(eval("input.?missing.orValue('d')", input())).isEqualTo("d");
        assertThat(eval("input.?nul.orValue('d')", input())).isNull();
        assertThat(eval("input.?customer.?id.orValue('none')", input())).isEqualTo("C1");
    }

    @Test
    void intAndDoubleCompare() {
        assertThat(eval("input.long == 7.0", input())).isEqualTo(true);
    }

    @Test
    void extensionsUsedByConfigAreAvailable() {
        assertThat(eval("regex.replace(input.name, r'\\s+', ' ').trim()", input())).isEqualTo("RAVI KUMAR");
        assertThat(eval("['a', 'b', 'c'].slice(1, 3).join(' ')", input())).isEqualTo("b c");
        assertThat(eval("cel.bind(p, input.items, p.size())", input())).isEqualTo(3L);
        assertThat(eval("'x'.upperAscii()", input())).isEqualTo("X");
        assertThat(eval("math.greatest(1, 5, 3)", input())).isEqualTo(5L);
    }

    @Test
    void missingKeyIsAnEvaluationErrorWithTheConfigPath() {
        CompiledExpr expr = engine.compile("input.missing", ScopeDecl.STEP, "axis.precheck.dedupe/steps[0].in.x");
        assertThatThrownBy(() -> engine.eval(expr, Map.of("input", input())))
                .isInstanceOf(ExprEvalException.class)
                .hasMessageContaining("axis.precheck.dedupe/steps[0].in.x")
                .hasMessageContaining("missing");
    }

    @Test
    void unknownMapLiteralKeyFails() {
        assertThatThrownBy(() -> eval("{'Y': 'SUCCESS'}['Q']", input())).isInstanceOf(ExprEvalException.class);
    }

    @Test
    void variablesOutsideTheScopeDoNotCompile() {
        assertThatThrownBy(() -> engine.compile("resp.status == 200", ScopeDecl.STEP, "p"))
                .isInstanceOf(ExprCompileException.class)
                .hasMessageContaining("resp");
        assertThat(engine.compile("resp.status == 200", ScopeDecl.HTTP_RESPONSE, "p")).isNotNull();
    }

    @Test
    void syntaxErrorsAndUnknownFunctionsDoNotCompile() {
        assertThatThrownBy(() -> engine.compile("input.a +", ScopeDecl.STEP, "p")).isInstanceOf(ExprCompileException.class);
        assertThatThrownBy(() -> engine.compile("rk.noSuchFunction(1)", ScopeDecl.STEP, "p"))
                .isInstanceOf(ExprCompileException.class);
        assertThatThrownBy(() -> engine.compile(" ", ScopeDecl.STEP, "p")).isInstanceOf(ExprCompileException.class);
    }

    @Test
    void expressionLengthLimitIsEnforcedAtCompileTime() {
        ExpressionEngine small = new CelExpressionEngine(new ExprLimits(20, 32, 1000), List.of());
        assertThatThrownBy(() -> small.compile("input.a + input.b + input.c + input.d", ScopeDecl.STEP, "p"))
                .isInstanceOf(ExprCompileException.class);
    }

    @Test
    void iterationLimitIsEnforcedAtRuntime() {
        ExpressionEngine small = new CelExpressionEngine(new ExprLimits(2000, 32, 2), List.of());
        CompiledExpr expr = small.compile("input.items.map(v, v)", ScopeDecl.STEP, "p");
        assertThatThrownBy(() -> small.eval(expr, Map.of("input", input()))).isInstanceOf(ExprEvalException.class);
    }

    @Test
    void javaObjectsAreRejected() {
        Map<String, Object> bad = new HashMap<>();
        bad.put("obj", new Object());
        assertThatThrownBy(() -> eval("input.obj", bad)).isInstanceOf(ExprEvalException.class)
                .hasMessageContaining("only plain data");
    }

    @Test
    void evalBoolRequiresABoolean() {
        CompiledExpr expr = engine.compile("input.long", ScopeDecl.STEP, "p");
        assertThatThrownBy(() -> engine.evalBool(expr, Map.of("input", input())))
                .isInstanceOf(ExprEvalException.class).hasMessageContaining("expected a bool");
    }
}
