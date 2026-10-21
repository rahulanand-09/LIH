package com.rupeek.l1.engine;

import com.rupeek.l1.expr.CelExpressionEngine;
import com.rupeek.l1.expr.ExprCompileException;
import com.rupeek.l1.expr.ExprLimits;
import com.rupeek.l1.expr.ExpressionEngine;
import com.rupeek.l1.expr.TemplateEvaluator;
import com.rupeek.l1.expr.fn.RkFunctions;
import com.rupeek.l1.model.ConfigJson;
import com.rupeek.l1.model.ConfigJson.ConfigException;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.Recipe;
import com.rupeek.l1.ops.OperationRegistry;
import com.rupeek.l1.ops.map.MapOperationExecutor;
import com.rupeek.l1.registry.InMemoryResourceProvider;
import com.rupeek.l1.registry.StaticRecipeSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigCompilerTest {

    private final ExpressionEngine expressions = new CelExpressionEngine(ExprLimits.DEFAULT, List.of(new RkFunctions()));
    private final ConfigCompiler compiler = new ConfigCompiler(expressions,
            new OperationRegistry(List.of(new MapOperationExecutor(new TemplateEvaluator(expressions)))),
            new InMemoryResourceProvider(Map.of(), "local", n -> null));

    private static final String MAP_OP = """
            {"op": "m", "kind": "MAP", "params": {"a": {}}, "template": {"x": "=params.a"}, "out": {"value": "result"}}""";

    private void compile(String recipeJson) {
        new StaticRecipeSource(List.of(recipe(recipeJson)), List.of(op(MAP_OP)), compiler);
    }

    private static String recipe(String steps, String extra) {
        return """
                {"recipe": "r", "outcomes": ["DONE", "NO"], "outputs": {"v": "string"}, %s
                 "steps": %s, "return": {"outcome": "DONE"}}""".formatted(extra, steps);
    }

    @Test
    void aValidRecipeCompiles() {
        compile(recipe("""
                [{"id": "a", "op": "m@1", "in": {"a": "'x'"}},
                 {"id": "b", "op": "m@1", "in": {"a": "steps.a.value.x"}, "onError": {"BUSINESS": "return:NO", "TECHNICAL": "raise"}}]""", ""));
    }

    @Test
    void structuralErrorsAreRejected() {
        Map<String, String> bad = Map.of(
                "duplicate id", recipe("[{\"id\": \"a\", \"op\": \"m@1\"}, {\"id\": \"a\", \"op\": \"m@1\"}]", ""),
                "op and return", recipe("[{\"id\": \"a\", \"op\": \"m@1\", \"return\": {\"outcome\": \"DONE\"}}]", ""),
                "neither op nor return", recipe("[{\"id\": \"a\"}]", ""),
                "unknown op", recipe("[{\"id\": \"a\", \"op\": \"missing@1\"}]", ""),
                "unversioned op ref", recipe("[{\"id\": \"a\", \"op\": \"m\"}]", ""),
                "unknown param", recipe("[{\"id\": \"a\", \"op\": \"m@1\", \"in\": {\"zz\": \"1\"}}]", ""),
                "unknown error class", recipe("[{\"id\": \"a\", \"op\": \"m@1\", \"onError\": {\"OOPS\": \"raise\"}}]", ""),
                "undeclared onError outcome", recipe("[{\"id\": \"a\", \"op\": \"m@1\", \"onError\": {\"BUSINESS\": \"return:MAYBE\"}}]", ""),
                "undeclared return outcome", recipe("[{\"id\": \"a\", \"return\": {\"outcome\": \"MAYBE\"}}]", ""),
                "undeclared output", recipe("[{\"id\": \"a\", \"return\": {\"outcome\": \"DONE\", \"out\": {\"zz\": \"1\"}}}]", ""));
        bad.forEach((name, json) -> assertThatThrownBy(() -> compile(json)).as(name).isInstanceOf(ConfigException.class));
    }

    @Test
    void dataflowCheckRejectsReadingLaterOrUnknownSteps() {
        assertThatThrownBy(() -> compile(recipe("""
                [{"id": "a", "op": "m@1", "in": {"a": "steps.b.value"}}, {"id": "b", "op": "m@1"}]""", "")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("steps.b");
        assertThatThrownBy(() -> compile(recipe("""
                [{"id": "a", "op": "m@1", "when": "steps.?nope.orValue(null) == null"}]""", "")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("steps.nope");
    }

    @Test
    void badExpressionsFailWithTheirConfigPath() {
        assertThatThrownBy(() -> compile(recipe("[{\"id\": \"a\", \"op\": \"m@1\", \"when\": \"input.\"}]", "")))
                .isInstanceOf(ExprCompileException.class).hasMessageContaining("r@1/steps[0:a].when");
    }

    @Test
    void timeoutBudgetCoversTheOperations() {
        CompiledRecipe r = compiler.compileRecipe(recipe(recipe("[{\"id\": \"a\", \"op\": \"m@1\"}]", "")),
                ref -> compiler.compileOperation(op(MAP_OP)));
        assertThat(r.timeoutBudgetMs()).isEqualTo(1200);
    }

    private static OperationDef op(String json) {
        return ConfigJson.operation(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "op.json").withVersion(1);
    }

    private static Recipe recipe(String json) {
        return ConfigJson.recipe(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "r.json").withVersion(1);
    }
}
