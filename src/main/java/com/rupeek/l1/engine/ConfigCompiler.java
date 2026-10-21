package com.rupeek.l1.engine;

import com.rupeek.l1.engine.CompiledRecipe.OnError;
import com.rupeek.l1.engine.CompiledRecipe.Return;
import com.rupeek.l1.expr.CompiledExpr;
import com.rupeek.l1.expr.ExpressionEngine;
import com.rupeek.l1.expr.ScopeDecl;
import com.rupeek.l1.model.ConfigJson.ConfigException;
import com.rupeek.l1.model.OpRef;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.OperationKind;
import com.rupeek.l1.model.ParamType;
import com.rupeek.l1.model.Recipe;
import com.rupeek.l1.model.Resource;
import com.rupeek.l1.model.ReturnSpec;
import com.rupeek.l1.model.Step;
import com.rupeek.l1.ops.ErrorClass;
import com.rupeek.l1.ops.OperationRegistry;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns config documents into runnable compiled form, validating them on the way. Used at load time and by the
 * publish pipeline, so a config error is found before any case runs.
 *
 * <p>Recipe checks: unique step ids; each step has an operation or a return, not both; operations exist;
 * {@code onError} names real error classes and declared outcomes; returned outcomes are declared; returned
 * outputs are declared; a step only reads {@code steps.<id>} of earlier operation steps (dataflow check).
 */
public final class ConfigCompiler {

    private static final Pattern STEP_REF = Pattern.compile("steps\\s*\\.\\s*\\??([A-Za-z_][A-Za-z0-9_]*)");
    private static final long DEFAULT_HTTP_READ_MS = 30_000;
    private static final long DEFAULT_HTTP_CONNECT_MS = 10_000;
    private static final long MONGO_BUDGET_MS = 10_000;
    private static final long LOCAL_BUDGET_MS = 1_000;

    private final ExpressionEngine expressions;
    private final OperationRegistry operations;
    private final ResourceProvider resources;

    public ConfigCompiler(ExpressionEngine expressions, OperationRegistry operations, ResourceProvider resources) {
        this.expressions = expressions;
        this.operations = operations;
        this.resources = resources;
    }

    public CompiledOperation compileOperation(OperationDef def) {
        if (def.op() == null || def.op().isBlank()) {
            throw new ConfigException("operation has no 'op' name");
        }
        if (def.version() == null) {
            throw new ConfigException(def.op() + ": operation has no version");
        }
        String p = def.op() + "@" + def.version();
        if (def.resource() != null) {
            resources.resource(def.resource());   // must exist
        }
        ScopeDecl outScope = def.kind() == OperationKind.HTTP ? ScopeDecl.HTTP_RESPONSE : ScopeDecl.RESULT;
        Map<String, CompiledExpr> out = new LinkedHashMap<>();
        def.out().forEach((k, src) -> out.put(k, expressions.compile(src, outScope, p + "/out." + k)));
        for (String r : def.required()) {
            if (!out.containsKey(r)) {
                throw new ConfigException(p + "/required: '" + r + "' is not an output");
            }
        }
        String auditLiteral = null;
        CompiledExpr auditExpr = null;
        if (def.audit() != null) {
            if (def.audit().startsWith("=") && !def.audit().startsWith("==")) {
                auditExpr = expressions.compile(def.audit().substring(1), ScopeDecl.OPERATION, p + "/audit");
            } else {
                auditLiteral = def.audit().startsWith("==") ? def.audit().substring(1) : def.audit();
            }
        }
        return new CompiledOperation(
                new OpRef(def.op(), def.version()),
                def,
                operations.prepare(def),
                def.success() == null ? null : expressions.compile(def.success(), outScope, p + "/success"),
                def.classify() == null ? null : expressions.compile(def.classify(), outScope, p + "/classify"),
                out,
                def.required(),
                auditLiteral,
                auditExpr);
    }

    public CompiledRecipe compileRecipe(Recipe recipe, Function<OpRef, CompiledOperation> operationLookup) {
        if (recipe.recipe() == null || recipe.recipe().isBlank()) {
            throw new ConfigException("recipe has no 'recipe' name");
        }
        if (recipe.version() == null) {
            throw new ConfigException(recipe.recipe() + ": recipe has no version");
        }
        String r = recipe.recipe() + "@" + recipe.version();
        Set<String> outcomes = new LinkedHashSet<>(recipe.outcomes());
        if (outcomes.isEmpty()) {
            throw new ConfigException(r + "/outcomes: at least one outcome must be declared");
        }
        if (recipe.returnSpec() == null) {
            throw new ConfigException(r + ": recipe needs a final 'return'");
        }
        recipe.resources().values().forEach(resources::resource);   // must exist
        Map<String, ParamType> inputs = types(recipe.inputs(), r + "/inputs");
        Map<String, ParamType> outputs = types(recipe.outputs(), r + "/outputs");

        Set<String> allOpSteps = new HashSet<>();
        recipe.steps().forEach(s -> {
            if (s.op() != null) {
                allOpSteps.add(s.id());
            }
        });

        List<CompiledRecipe.Step> steps = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> earlierOpSteps = new HashSet<>();
        long budget = 0;
        for (int i = 0; i < recipe.steps().size(); i++) {
            Step s = recipe.steps().get(i);
            String p = r + "/steps[" + i + "]";
            if (s.id() == null || s.id().isBlank()) {
                throw new ConfigException(p + ": step has no 'id'");
            }
            if (!ids.add(s.id())) {
                throw new ConfigException(p + ": duplicate step id '" + s.id() + "'");
            }
            p = r + "/steps[" + i + ":" + s.id() + "]";
            if ((s.op() == null) == (s.returnSpec() == null)) {
                throw new ConfigException(p + ": a step needs exactly one of 'op' or 'return'");
            }
            CompiledExpr when = s.when() == null ? null : step(s.when(), p + ".when", earlierOpSteps);
            Map<String, CompiledExpr> in = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : s.in().entrySet()) {
                in.put(e.getKey(), step(e.getValue(), p + ".in." + e.getKey(), earlierOpSteps));
            }
            if (s.isReturn()) {
                if (!s.in().isEmpty() || !s.onError().isEmpty()) {
                    throw new ConfigException(p + ": a return step cannot have 'in' or 'onError'");
                }
                steps.add(new CompiledRecipe.Step(s.id(), null, null, when, Map.of(),
                        compileReturn(s.returnSpec(), p + ".return", outcomes, outputs, earlierOpSteps), Map.of()));
                continue;
            }
            OpRef ref;
            try {
                ref = OpRef.parse(s.op());
            } catch (IllegalArgumentException e) {
                throw new ConfigException(p + ".op: " + e.getMessage());
            }
            CompiledOperation op = operationLookup.apply(ref);
            if (op == null) {
                throw new ConfigException(p + ".op: unknown operation " + ref);
            }
            for (String param : in.keySet()) {
                if (!op.def().params().isEmpty() && !op.def().params().containsKey(param)) {
                    throw new ConfigException(p + ".in." + param + ": not a parameter of " + ref);
                }
            }
            steps.add(new CompiledRecipe.Step(s.id(), ref, op, when, in, null, onError(s, p, outcomes)));
            earlierOpSteps.add(s.id());
            budget += budgetMs(op.def());
        }

        Map<String, CompiledExpr> auditKeys = new LinkedHashMap<>();
        recipe.auditKeys().forEach((k, src) -> auditKeys.put(k, step(src, r + "/auditKeys." + k, allOpSteps)));
        Return ret = compileReturn(recipe.returnSpec(), r + "/return", outcomes, outputs, allOpSteps);

        return new CompiledRecipe(recipe.recipe(), recipe.version(), recipe.lender(), recipe.resources(), inputs,
                outputs, outcomes, auditKeys, steps, ret, Math.round(budget * 1.2));
    }

    private Return compileReturn(ReturnSpec spec, String p, Set<String> outcomes, Map<String, ParamType> outputs,
                                 Set<String> visibleSteps) {
        if (spec.outcome() == null || !outcomes.contains(spec.outcome())) {
            throw new ConfigException(p + ".outcome: '" + spec.outcome() + "' is not a declared outcome " + outcomes);
        }
        Map<String, CompiledExpr> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : spec.out().entrySet()) {
            if (!outputs.containsKey(e.getKey())) {
                throw new ConfigException(p + ".out." + e.getKey() + ": not a declared output");
            }
            out.put(e.getKey(), step(e.getValue(), p + ".out." + e.getKey(), visibleSteps));
        }
        return new Return(spec.outcome(), out);
    }

    private Map<ErrorClass, OnError> onError(Step s, String p, Set<String> outcomes) {
        Map<ErrorClass, OnError> out = new EnumMap<>(ErrorClass.class);
        s.onError().forEach((cls, action) -> {
            ErrorClass errorClass;
            try {
                errorClass = ErrorClass.valueOf(cls);
            } catch (IllegalArgumentException e) {
                throw new ConfigException(p + ".onError: unknown error class '" + cls + "'");
            }
            if ("raise".equals(action)) {
                out.put(errorClass, OnError.RAISE);
            } else if (action != null && action.startsWith("return:")) {
                String outcome = action.substring("return:".length());
                if (!outcomes.contains(outcome)) {
                    throw new ConfigException(p + ".onError." + cls + ": '" + outcome + "' is not a declared outcome");
                }
                out.put(errorClass, new OnError(outcome));
            } else {
                throw new ConfigException(p + ".onError." + cls + ": must be 'raise' or 'return:<OUTCOME>'");
            }
        });
        return out;
    }

    /** Compiles a step-scope expression and checks it only reads steps that have already run. */
    private CompiledExpr step(String source, String path, Set<String> visibleSteps) {
        CompiledExpr expr = expressions.compile(source, ScopeDecl.STEP, path);
        Matcher m = STEP_REF.matcher(source);
        while (m.find()) {
            if (!visibleSteps.contains(m.group(1))) {
                throw new ConfigException(path + ": reads steps." + m.group(1)
                        + ", which is not an earlier operation step");
            }
        }
        return expr;
    }

    private static Map<String, ParamType> types(Map<String, String> declared, String p) {
        Map<String, ParamType> out = new LinkedHashMap<>();
        declared.forEach((k, v) -> {
            try {
                out.put(k, ParamType.of(v));
            } catch (RuntimeException e) {
                throw new ConfigException(p + "." + k + ": unknown type '" + v + "'");
            }
        });
        return out;
    }

    private long budgetMs(OperationDef def) {
        return switch (def.kind()) {
            case HTTP -> {
                Resource.TimeoutSpec t = def.resource() == null ? null : resources.resource(def.resource()).timeoutMs();
                long connect = t != null && t.connect() != null ? t.connect() : DEFAULT_HTTP_CONNECT_MS;
                long read = def.timeoutMs() != null ? def.timeoutMs()
                        : t != null && t.read() != null ? t.read() : DEFAULT_HTTP_READ_MS;
                yield connect + read;
            }
            case MONGO -> MONGO_BUDGET_MS;
            case MAP, GEN -> LOCAL_BUDGET_MS;
        };
    }
}
