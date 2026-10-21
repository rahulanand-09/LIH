package com.rupeek.l1.engine;

import com.rupeek.l1.api.StepTrace;
import com.rupeek.l1.expr.CompiledExpr;
import com.rupeek.l1.expr.ExprEvalException;
import com.rupeek.l1.expr.ExpressionEngine;
import com.rupeek.l1.intercept.PiiMasker;
import com.rupeek.l1.model.ParamSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * State of one recipe attempt. Lives only in worker memory (step outputs may hold customer PII); only the final
 * {@code TaskResult} reaches Temporal history.
 */
public final class StepContext {

    private final CompiledRecipe recipe;
    private final String caseId;
    private final ExpressionEngine expressions;
    private final Map<String, Object> input;
    private final Map<String, Object> steps = new LinkedHashMap<>();
    private final Map<String, Object> env;
    private final Map<String, Object> sys;
    private final List<StepTrace> traces = new ArrayList<>();
    private final Set<String> piiValues = new HashSet<>();

    StepContext(CompiledRecipe recipe, String caseId, Map<String, Object> input, Map<String, Object> env,
                Map<String, Object> sys, ExpressionEngine expressions) {
        this.recipe = recipe;
        this.caseId = caseId;
        this.input = input;
        this.env = env;
        this.sys = sys;
        this.expressions = expressions;
    }

    public CompiledRecipe recipe() {
        return recipe;
    }

    public String caseId() {
        return caseId;
    }

    public Map<String, Object> sys() {
        return sys;
    }

    /** Variables for step-scope expressions. */
    public Map<String, Object> vars() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("input", input);
        vars.put("steps", Collections.unmodifiableMap(steps));
        vars.put("env", env);
        vars.put("sys", sys);
        return vars;
    }

    boolean evalBool(CompiledExpr expr) {
        return expressions.evalBool(expr, vars());
    }

    Map<String, Object> evalMap(Map<String, CompiledExpr> exprs) {
        Map<String, Object> vars = vars();
        Map<String, Object> out = new LinkedHashMap<>();
        exprs.forEach((k, e) -> out.put(k, expressions.eval(e, vars)));
        return out;
    }

    /** Evaluates the recipe's audit keys; never fails (an audit key is never worth failing a call). */
    String auditKey(String name) {
        CompiledExpr expr = recipe.auditKeys().get(name);
        if (expr == null) {
            return null;
        }
        try {
            Object v = expressions.eval(expr, vars());
            return v == null ? null : String.valueOf(v);
        } catch (ExprEvalException e) {
            return null;
        }
    }

    void record(String stepId, Map<String, Object> outputs) {
        steps.put(stepId, outputs);
    }

    void trace(String stepId, String opRef, String status, long durationMs) {
        traces.add(new StepTrace(stepId, opRef, status, durationMs));
    }

    List<StepTrace> traces() {
        return List.copyOf(traces);
    }

    /**
     * Remembers PII values seen in operation parameters (values under known PII keys, and plain-string
     * {@code pii: true} parameters), for the output guard and failure-message masking.
     */
    void notePii(Map<String, ParamSpec> declared, Map<String, Object> params) {
        params.forEach((name, value) -> {
            ParamSpec spec = declared.get(name);
            PiiMasker.collectPiiValues(value, spec != null && spec.pii(), piiValues);
        });
    }

    Set<String> piiValues() {
        return Collections.unmodifiableSet(piiValues);
    }
}
