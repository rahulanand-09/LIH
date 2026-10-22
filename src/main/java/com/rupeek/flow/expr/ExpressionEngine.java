package com.rupeek.flow.expr;

import java.util.Map;

/**
 * Compiles and evaluates config expressions. The rest of {@code com.rupeek.flow}'s native executors depend on
 * this interface, not on CEL, so the library can be upgraded or replaced in one place.
 */
public interface ExpressionEngine {

    /**
     * Compiles once, at node-config load time.
     *
     * @param configPath where the expression lives in config, e.g. {@code nodeId.fields.success}; carried into
     *                   every error
     * @throws ExprCompileException on syntax errors, unknown variables or functions, or exceeded limits
     */
    CompiledExpr compile(String source, ScopeDecl scope, String configPath);

    /**
     * Evaluates with plain-data variables (maps, lists, strings, numbers, booleans, nulls).
     * Returns plain Java data; CEL null becomes {@code null}.
     *
     * @throws ExprEvalException on any evaluation failure (missing key, no matching overload, function error)
     */
    Object eval(CompiledExpr expr, Map<String, Object> vars);

    /** Evaluates and requires a boolean result. */
    default boolean evalBool(CompiledExpr expr, Map<String, Object> vars) {
        Object value = eval(expr, vars);
        if (value instanceof Boolean b) {
            return b;
        }
        throw new ExprEvalException(expr.configPath(), expr.source(),
                "expected a bool but got " + (value == null ? "null" : value.getClass().getSimpleName()), null);
    }
}
