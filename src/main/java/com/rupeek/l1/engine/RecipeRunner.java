package com.rupeek.l1.engine;

import com.rupeek.l1.api.TaskRequest;
import com.rupeek.l1.api.TaskResult;
import com.rupeek.l1.engine.CompiledRecipe.OnError;
import com.rupeek.l1.expr.CompiledExpr;
import com.rupeek.l1.expr.ExprEvalException;
import com.rupeek.l1.expr.ExpressionEngine;
import com.rupeek.l1.intercept.PiiMasker;
import com.rupeek.l1.model.ConfigJson.ConfigException;
import com.rupeek.l1.model.ParamType;
import com.rupeek.l1.ops.ErrorClass;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one recipe attempt: steps in order, each with its guard, then the operation; early returns; {@code onError}
 * routing; finally the output guard. Every failure leaves as an {@link L1Failure} with a PII-masked message.
 *
 * <p>Temporal retries re-run the whole recipe; recipes are written to be safe to re-run (check what exists, reuse
 * stored ids, upsert before calling the lender). There is no step journal.
 */
public final class RecipeRunner {

    private static final Logger LOG = LoggerFactory.getLogger(RecipeRunner.class);

    /** Called before each step; in an activity it heartbeats (and surfaces cancellation). */
    @FunctionalInterface
    public interface Heartbeat {
        Heartbeat NONE = stepId -> { };

        void beat(String stepId);
    }

    private final RecipeSource recipes;
    private final ResourceProvider resources;
    private final OperationInvoker invoker;
    private final ExpressionEngine expressions;
    private final OutputGuard outputGuard;
    private final PiiMasker masker;
    private final MeterRegistry meters;
    private final Clock clock;

    public RecipeRunner(RecipeSource recipes, ResourceProvider resources, OperationInvoker invoker,
                        ExpressionEngine expressions, OutputGuard outputGuard, PiiMasker masker,
                        MeterRegistry meters, Clock clock) {
        this.recipes = recipes;
        this.resources = resources;
        this.invoker = invoker;
        this.expressions = expressions;
        this.outputGuard = outputGuard;
        this.masker = masker;
        this.meters = meters;
        this.clock = clock;
    }

    public TaskResult run(TaskRequest request, Heartbeat heartbeat) {
        Timer.Sample sample = Timer.start(meters);
        String result = "error";
        CompiledRecipe recipe = null;
        try {
            recipe = load(request);
            TaskResult out = runSteps(recipe, request, heartbeat);
            result = out.outcome();
            return out;
        } catch (L1Failure f) {
            result = f.errorClass().name();
            throw f;
        } finally {
            sample.stop(Timer.builder("lih.l1.task")
                    .tag("recipe", String.valueOf(request.recipeRef()))
                    .tag("version", String.valueOf(request.recipeVersion()))
                    .tag("lender", recipe == null ? "unknown" : String.valueOf(recipe.lender()))
                    .tag("result", result)
                    .register(meters));
        }
    }

    private CompiledRecipe load(TaskRequest request) {
        try {
            return recipes.recipe(request.recipeRef(), request.recipeVersion());
        } catch (ConfigException e) {
            throw L1Failure.permanent(e.getMessage(), e);
        }
    }

    private TaskResult runSteps(CompiledRecipe recipe, TaskRequest request, Heartbeat heartbeat) {
        Map<String, Object> sys = new HashMap<>();
        sys.put("now", clock.instant());
        sys.put("caseId", request.caseId());
        sys.put("runNo", (long) request.runNo());
        StepContext ctx = new StepContext(recipe, request.caseId(), validateInputs(recipe, request.inputs()),
                recipeEnv(recipe), sys, expressions);

        for (CompiledRecipe.Step step : recipe.steps()) {
            heartbeat.beat(step.id());
            long start = System.nanoTime();
            String opRef = step.opRef() == null ? null : step.opRef().toString();
            try {
                if (step.when() != null && !ctx.evalBool(step.when())) {
                    ctx.trace(step.id(), opRef, "SKIPPED", elapsed(start));
                    logStep(request, step, opRef, "skipped", start);
                    continue;
                }
                if (step.isReturn()) {
                    ctx.trace(step.id(), null, "RETURNED", elapsed(start));
                    logStep(request, step, null, "return " + step.returnSpec().outcome(), start);
                    return finish(recipe, ctx, step.returnSpec().outcome(), step.returnSpec().out());
                }
                Map<String, Object> params = ctx.evalMap(step.in());
                ctx.record(step.id(), invoker.invoke(step, params, ctx));
                ctx.trace(step.id(), opRef, "OK", elapsed(start));
                logStep(request, step, opRef, "ok", start);
            } catch (ExprEvalException e) {
                ctx.trace(step.id(), opRef, "PERMANENT", elapsed(start));
                logStep(request, step, opRef, "PERMANENT (expression)", start);
                throw masked(new L1Failure(ErrorClass.PERMANENT, e.getMessage(), step.id(), opRef, null, e), ctx, step.id(), opRef);
            } catch (L1Failure f) {
                ctx.trace(step.id(), opRef, f.errorClass().name(), elapsed(start));
                OnError action = step.onError().getOrDefault(f.errorClass(), OnError.RAISE);
                if (!action.raises()) {
                    logStep(request, step, opRef, f.errorClass().name() + " → onError return " + action.returnOutcome(), start);
                    return finish(recipe, ctx, action.returnOutcome(), Map.of());
                }
                logStep(request, step, opRef, f.errorClass().name() + " → raise", start);
                throw masked(f, ctx, step.id(), opRef);
            }
        }
        return finish(recipe, ctx, recipe.returnSpec().outcome(), recipe.returnSpec().out());
    }

    private static void logStep(TaskRequest request, CompiledRecipe.Step step, String opRef, String what, long start) {
        LOG.info("[L1 {}]   {} │ step {} {} ({} ms){}", request.caseId(), request.recipeRef(), step.id(), what,
                (System.nanoTime() - start) / 1_000_000, opRef == null ? "" : "  [" + opRef + "]");
    }

    private TaskResult finish(CompiledRecipe recipe, StepContext ctx, String outcome,
                              Map<String, CompiledExpr> out) {
        Map<String, Object> outputs;
        try {
            outputs = ctx.evalMap(out);
        } catch (ExprEvalException e) {
            throw masked(L1Failure.permanent("return outputs: " + e.getMessage(), e), ctx, null, null);
        }
        try {
            outputGuard.check(outputs, recipe, ctx.piiValues());
        } catch (L1Failure f) {
            throw masked(f, ctx, null, null);
        }
        return new TaskResult(outcome, outputs, recipe.ref(), recipe.version(), ctx.traces());
    }

    private Map<String, Object> validateInputs(CompiledRecipe recipe, Map<String, Object> given) {
        Map<String, Object> inputs = new LinkedHashMap<>(given == null ? Map.of() : given);
        for (Map.Entry<String, ParamType> e : recipe.inputs().entrySet()) {
            Object value = inputs.get(e.getKey());
            if (value == null) {
                throw L1Failure.permanent("input '" + e.getKey() + "' is missing", null);
            }
            if (value instanceof Integer i && e.getValue() == ParamType.INT) {
                inputs.put(e.getKey(), i.longValue());
            } else if (!OutputGuard.matches(e.getValue(), value)) {
                throw L1Failure.permanent("input '" + e.getKey() + "' is not a " + e.getValue().configName(), null);
            }
        }
        return inputs;
    }

    private Map<String, Object> recipeEnv(CompiledRecipe recipe) {
        Map<String, Object> env = new HashMap<>();
        try {
            recipe.resources().forEach((alias, id) -> env.put(alias, resources.values(resources.resource(id))));
        } catch (ConfigException e) {
            throw L1Failure.permanent(e.getMessage(), e);
        }
        return env;
    }

    private L1Failure masked(L1Failure f, StepContext ctx, String stepId, String opRef) {
        return f.at(stepId, opRef, masker.maskText(f.getMessage(), ctx.piiValues()));
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
