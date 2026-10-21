package com.rupeek.l1.engine;

import com.rupeek.l1.expr.CompiledExpr;
import com.rupeek.l1.model.OpRef;
import com.rupeek.l1.model.ParamType;
import com.rupeek.l1.ops.ErrorClass;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A recipe version ready to run, with its operations resolved and every expression compiled.
 *
 * @param resources       alias to resource id, for recipe-level {@code env.<alias>}
 * @param timeoutBudgetMs suggested activity start-to-close timeout: the operations' worst-case time plus 20%
 */
public record CompiledRecipe(
        String ref,
        int version,
        String lender,
        Map<String, String> resources,
        Map<String, ParamType> inputs,
        Map<String, ParamType> outputs,
        Set<String> outcomes,
        Map<String, CompiledExpr> auditKeys,
        List<Step> steps,
        Return returnSpec,
        long timeoutBudgetMs) {

    /** A compiled step: runs {@code op}, or is an early return ({@code returnSpec} set). */
    public record Step(
            String id,
            OpRef opRef,
            CompiledOperation op,
            CompiledExpr when,
            Map<String, CompiledExpr> in,
            Return returnSpec,
            Map<ErrorClass, OnError> onError) {

        public boolean isReturn() {
            return returnSpec != null;
        }
    }

    /** A literal outcome and outputs built by compiled expressions. */
    public record Return(String outcome, Map<String, CompiledExpr> out) {
    }

    /** What to do when a step fails with a given class: raise (default) or return an outcome. */
    public record OnError(String returnOutcome) {

        public static final OnError RAISE = new OnError(null);

        public boolean raises() {
            return returnOutcome == null;
        }
    }
}
