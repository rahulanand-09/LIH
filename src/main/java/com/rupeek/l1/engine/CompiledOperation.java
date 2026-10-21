package com.rupeek.l1.engine;

import com.rupeek.l1.expr.CompiledExpr;
import com.rupeek.l1.model.OpRef;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.ops.PreparedOperation;

import java.util.List;
import java.util.Map;

/**
 * An operation version ready to run: executor-prepared, every expression compiled. Immutable; cached forever
 * because published versions never change.
 *
 * @param success      null when the operation has no success check
 * @param classify     null to use the default classification
 * @param auditLiteral audit request type when written as a literal; null otherwise
 * @param auditExpr    audit request type when written as {@code =CEL}; null otherwise
 */
public record CompiledOperation(
        OpRef ref,
        OperationDef def,
        PreparedOperation prepared,
        CompiledExpr success,
        CompiledExpr classify,
        Map<String, CompiledExpr> out,
        List<String> required,
        String auditLiteral,
        CompiledExpr auditExpr) {
}
