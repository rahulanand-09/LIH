package com.rupeek.l1.engine;

import com.rupeek.l1.api.L1FailureType;
import com.rupeek.l1.api.StepTrace;
import com.rupeek.l1.api.TaskRequest;
import com.rupeek.l1.api.TaskResult;
import com.rupeek.l1.expr.CelExpressionEngine;
import com.rupeek.l1.expr.ExprLimits;
import com.rupeek.l1.expr.ExpressionEngine;
import com.rupeek.l1.expr.TemplateEvaluator;
import com.rupeek.l1.expr.fn.RkFunctions;
import com.rupeek.l1.intercept.InterceptorChain;
import com.rupeek.l1.intercept.PiiMasker;
import com.rupeek.l1.model.ConfigJson;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.Recipe;
import com.rupeek.l1.ops.ErrorClass;
import com.rupeek.l1.ops.OperationException;
import com.rupeek.l1.ops.OperationRegistry;
import com.rupeek.l1.ops.TransportException;
import com.rupeek.l1.ops.gen.GenOperationExecutor;
import com.rupeek.l1.ops.map.MapOperationExecutor;
import com.rupeek.l1.registry.InMemoryResourceProvider;
import com.rupeek.l1.registry.StaticRecipeSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.rupeek.l1.engine.ScriptedExecutor.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeRunnerTest {

    private static final String FETCH = """
            {"op": "t.fetch", "kind": "HTTP", "params": {"id": {}, "pan": {"type": "string", "pii": true}},
             "request": {"method": "POST", "path": "/fetch"},
             "success": "resp.status == 200", "out": {"v": "resp.body.?v.orValue(null)"}}""";
    private static final String WRITE = """
            {"op": "t.write", "kind": "HTTP", "params": {"v": {}},
             "request": {"method": "POST", "path": "/write"},
             "success": "resp.status == 200", "out": {"ok": "true"}}""";
    private static final String NEEDS = """
            {"op": "t.needs", "kind": "HTTP", "request": {"method": "GET", "path": "/needs"},
             "success": "resp.status == 200", "out": {"x": "resp.body.?x.orValue(null)"}, "required": ["x"]}""";
    private static final String MAP = """
            {"op": "t.map", "kind": "MAP", "params": {"v": {}}, "template": {"upper": "=params.v.upperAscii()"},
             "out": {"value": "result"}}""";
    private static final String RECIPE = """
            {"recipe": "t.flow", "lender": "testlender",
             "inputs": {"id": "string", "pan": "string"},
             "outputs": {"v": "string", "upper": "string", "leak": "string"},
             "outcomes": ["DONE", "EARLY", "REJECTED"],
             "steps": [
               {"id": "fetch", "op": "t.fetch@1", "in": {"id": "input.id", "pan": "input.pan"}},
               {"id": "early", "when": "input.id == 'early'", "return": {"outcome": "EARLY", "out": {"v": "steps.fetch.v"}}},
               {"id": "skipMe", "op": "t.write@1", "when": "false", "in": {"v": "'never'"}},
               {"id": "write", "op": "t.write@1", "in": {"v": "steps.fetch.v"}, "onError": {"BUSINESS": "return:REJECTED"}},
               {"id": "map", "op": "t.map@1", "in": {"v": "steps.fetch.v"}}
             ],
             "return": {"outcome": "DONE", "out": {"v": "steps.fetch.v", "upper": "steps.map.value.upper"}}}""";

    private final ScriptedExecutor http = new ScriptedExecutor();
    private final ExpressionEngine expressions = new CelExpressionEngine(ExprLimits.DEFAULT, List.of(new RkFunctions()));

    private RecipeRunner runner(String recipeJson, String... ops) {
        InMemoryResourceProvider resources = new InMemoryResourceProvider(Map.of(), "local", n -> null);
        OperationRegistry registry = new OperationRegistry(List.of(http,
                new MapOperationExecutor(new TemplateEvaluator(expressions)), new GenOperationExecutor()));
        ConfigCompiler compiler = new ConfigCompiler(expressions, registry, resources);
        List<OperationDef> defs = java.util.Arrays.stream(ops).map(RecipeRunnerTest::op).toList();
        StaticRecipeSource source = new StaticRecipeSource(List.of(recipe(recipeJson)), defs, compiler);
        OperationInvoker invoker = new OperationInvoker(registry, resources, new InterceptorChain(List.of()),
                expressions, new ErrorClassifier());
        return new RecipeRunner(source, resources, invoker, expressions, new OutputGuard(), new PiiMasker(),
                new SimpleMeterRegistry(), Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC));
    }

    private RecipeRunner defaultRunner() {
        return runner(RECIPE, FETCH, WRITE, MAP);
    }

    private static TaskRequest request(String id) {
        return new TaskRequest("t.flow", 1, "case-1", 1, Map.of("id", id, "pan", "ABCDE1234F"));
    }

    // ------------------------------------------------------------------ happy path, guards, early return

    @Test
    void runsStepsInOrderAndReturnsDeclaredOutputs() {
        http.on("t.fetch", respond(200, Map.of("v", "hello"))).on("t.write", respond(200, null));

        TaskResult result = defaultRunner().run(request("LR-1"), RecipeRunner.Heartbeat.NONE);

        assertThat(result.outcome()).isEqualTo("DONE");
        assertThat(result.outputs()).isEqualTo(Map.of("v", "hello", "upper", "HELLO"));
        assertThat(result.steps()).extracting(StepTrace::stepId, StepTrace::status).containsExactly(
                org.assertj.core.groups.Tuple.tuple("fetch", "OK"),
                org.assertj.core.groups.Tuple.tuple("early", "SKIPPED"),
                org.assertj.core.groups.Tuple.tuple("skipMe", "SKIPPED"),
                org.assertj.core.groups.Tuple.tuple("write", "OK"),
                org.assertj.core.groups.Tuple.tuple("map", "OK"));
        assertThat(http.calls).extracting(ScriptedExecutor.Call::op).containsExactly("t.fetch", "t.write");
        assertThat(http.calls.get(1).params()).isEqualTo(Map.of("v", "hello"));
    }

    @Test
    void earlyReturnStopsTheRecipe() {
        http.on("t.fetch", respond(200, Map.of("v", "x")));

        TaskResult result = defaultRunner().run(request("early"), RecipeRunner.Heartbeat.NONE);

        assertThat(result.outcome()).isEqualTo("EARLY");
        assertThat(result.outputs()).isEqualTo(Map.of("v", "x"));
        assertThat(http.calls).hasSize(1);
        assertThat(result.steps().get(1).status()).isEqualTo("RETURNED");
    }

    @Test
    void heartbeatIsCalledBeforeEveryStep() {
        http.on("t.fetch", respond(200, Map.of("v", "x"))).on("t.write", respond(200, null));
        List<String> beats = new java.util.ArrayList<>();
        defaultRunner().run(request("LR-1"), beats::add);
        assertThat(beats).containsExactly("fetch", "early", "skipMe", "write", "map");
    }

    // ------------------------------------------------------------------ classification and onError

    @Test
    void businessFailureCanBeTurnedIntoAnOutcome() {
        http.on("t.fetch", respond(200, Map.of("v", "x"))).on("t.write", respond(422, Map.of("error", "no")));

        TaskResult result = defaultRunner().run(request("LR-1"), RecipeRunner.Heartbeat.NONE);

        assertThat(result.outcome()).isEqualTo("REJECTED");
        assertThat(result.outputs()).isEmpty();
        assertThat(result.steps()).extracting(StepTrace::status).contains("BUSINESS");
    }

    @Test
    void defaultClassificationOfFailedResponses() {
        assertThat(failureFor(respond(503, null))).isEqualTo(ErrorClass.TECHNICAL);
        assertThat(failureFor(respond(429, null))).isEqualTo(ErrorClass.TECHNICAL);
        assertThat(failureFor(respond(500, null))).isEqualTo(ErrorClass.UNKNOWN);    // POST: may have been processed
        assertThat(failureFor(respond(400, null))).isEqualTo(ErrorClass.BUSINESS);   // fetch has no onError
        assertThat(failureFor(p -> { throw new TransportException(false, true, "connect timeout", null); }))
                .isEqualTo(ErrorClass.TECHNICAL);
        assertThat(failureFor(p -> { throw new TransportException(true, true, "read timeout", null); }))
                .isEqualTo(ErrorClass.UNKNOWN);
    }

    private ErrorClass failureFor(ScriptedExecutor.Answer fetchAnswer) {
        http.on("t.fetch", fetchAnswer);
        try {
            defaultRunner().run(request("LR-1"), RecipeRunner.Heartbeat.NONE);
            throw new AssertionError("expected a failure");
        } catch (L1Failure f) {
            assertThat(f.stepId()).isEqualTo("fetch");
            assertThat(f.opRef()).isEqualTo("t.fetch@1");
            return f.errorClass();
        }
    }

    // ------------------------------------------------------------------ permanent failures

    @Test
    void missingOrMistypedInputsArePermanent() {
        RecipeRunner r = defaultRunner();
        assertThatThrownBy(() -> r.run(new TaskRequest("t.flow", 1, "c", 1, Map.of("pan", "x")), RecipeRunner.Heartbeat.NONE))
                .isInstanceOf(L1Failure.class).hasMessageContaining("input 'id' is missing");
        assertThatThrownBy(() -> r.run(new TaskRequest("t.flow", 1, "c", 1, Map.of("id", 5, "pan", "x")), RecipeRunner.Heartbeat.NONE))
                .isInstanceOf(L1Failure.class).hasMessageContaining("input 'id' is not a string");
        assertThatThrownBy(() -> r.run(new TaskRequest("t.flow", 9, "c", 1, Map.of("id", "x", "pan", "x")), RecipeRunner.Heartbeat.NONE))
                .isInstanceOf(L1Failure.class).hasMessageContaining("unknown recipe t.flow@9");
    }

    @Test
    void expressionFailureAtRuntimeIsPermanentAndNamesTheStep() {
        http.on("t.fetch", respond(200, Map.of()));        // no 'v': later steps read a null value
        RecipeRunner r = runner(RECIPE.replace("\"in\": {\"v\": \"steps.fetch.v\"}, \"onError\"",
                "\"in\": {\"v\": \"steps.fetch.v.size()\"}, \"onError\""), FETCH, WRITE, MAP);

        assertThatThrownBy(() -> r.run(request("LR-1"), RecipeRunner.Heartbeat.NONE))
                .isInstanceOf(L1Failure.class)
                .satisfies(e -> {
                    L1Failure f = (L1Failure) e;
                    assertThat(f.errorClass()).isEqualTo(ErrorClass.PERMANENT);
                    assertThat(f.stepId()).isEqualTo("write");
                });
    }

    @Test
    void missingRequiredOutputIsPermanent() {
        http.on("t.needs", respond(200, Map.of()));
        RecipeRunner r = runner("""
                {"recipe": "t.flow", "outcomes": ["DONE"], "steps": [{"id": "n", "op": "t.needs@1"}],
                 "return": {"outcome": "DONE"}}""", NEEDS);
        assertThatThrownBy(() -> r.run(new TaskRequest("t.flow", 1, "c", 1, Map.of()), RecipeRunner.Heartbeat.NONE))
                .isInstanceOf(L1Failure.class).hasMessageContaining("required output 'x' is missing");
    }

    @Test
    void piiNeverLeavesInOutputsOrFailureMessages() {
        http.on("t.fetch", respond(200, Map.of("v", "ABCDE1234F")));        // lender echoes the PAN back
        assertThatThrownBy(() -> defaultRunner().run(request("early"), RecipeRunner.Heartbeat.NONE))
                .isInstanceOf(L1Failure.class).hasMessageContaining("contains a value marked as PII");

        http.on("t.fetch", p -> { throw new OperationException(ErrorClass.BUSINESS, "lender rejected PAN ABCDE1234F"); });
        assertThatThrownBy(() -> defaultRunner().run(request("LR-1"), RecipeRunner.Heartbeat.NONE))
                .isInstanceOf(L1Failure.class)
                .hasMessageContaining("XXXXXX234F")
                .hasMessageNotContaining("ABCDE1234F");
    }

    // ------------------------------------------------------------------ Temporal failure mapping

    @Test
    void onlyTechnicalFailuresAreRetryable() {
        for (ErrorClass cls : ErrorClass.values()) {
            ApplicationFailure af = new L1Failure(cls, "m", "s", "o@1", 500, null).toApplicationFailure("r", 1);
            assertThat(af.getType()).isEqualTo(cls.failureType().name());
            assertThat(af.isNonRetryable()).isEqualTo(cls != ErrorClass.TECHNICAL);
        }
        assertThat(ErrorClass.UNKNOWN.failureType()).isEqualTo(L1FailureType.L1_UNKNOWN);
    }

    private static OperationDef op(String json) {
        return ConfigJson.operation(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "op.json").withVersion(1);
    }

    private static Recipe recipe(String json) {
        return ConfigJson.recipe(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "recipe.json").withVersion(1);
    }
}
