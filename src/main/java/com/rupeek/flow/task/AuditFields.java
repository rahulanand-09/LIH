package com.rupeek.flow.task;

import com.rupeek.flow.expr.ExpressionEngine;
import com.rupeek.flow.expr.ScopeDecl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the audit-related keys shared by every HTTP/MONGO node's {@code fields}: {@code audit} (literal or
 * {@code =CEL}, against {@code {inputs, env}} — the request type for {@code LenderApiAuditLog}; null/absent
 * means "don't audit this call", matching L1's "GEN/MAP ops with no audit field are never audited"), optional
 * {@code auditKeys} ({@code {"loanRequestId": "=inputs.loanRequestId", "customerId": "=inputs.customerId"}} —
 * best-effort, like L1's {@code StepContext.auditKey}: a broken/missing expression is never worth failing the
 * call for, so it silently resolves to {@code null}), {@code lender}, and {@code piiPaths}.
 */
public final class AuditFields {

    private AuditFields() {
    }

    public static String requestType(Map<String, Object> fields, Map<String, Object> inputs, Map<String, Object> env,
                                     ExpressionEngine expressions, String nodeId) {
        Object audit = fields.get("audit");
        if (audit == null) {
            return null;
        }
        if (!(audit instanceof String s) || !s.startsWith("=")) {
            return audit == null ? null : String.valueOf(audit);
        }
        try {
            Map<String, Object> vars = new HashMap<>();
            vars.put("inputs", inputs);
            vars.put("env", env);
            Object v = expressions.eval(expressions.compile(s.substring(1), ScopeDecl.OPERATION, nodeId + ".fields.audit"), vars);
            return v == null ? null : String.valueOf(v);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String auditKey(Map<String, Object> fields, String key, Map<String, Object> inputs,
                                  ExpressionEngine expressions, String nodeId) {
        Object auditKeys = fields.get("auditKeys");
        if (!(auditKeys instanceof Map<?, ?> map) || !(map.get(key) instanceof String expr)) {
            return null;
        }
        String cel = expr.startsWith("=") ? expr.substring(1) : expr;
        try {
            Map<String, Object> vars = new HashMap<>();
            vars.put("inputs", inputs);
            Object v = expressions.eval(expressions.compile(cel, ScopeDecl.OPERATION, nodeId + ".fields.auditKeys." + key), vars);
            return v == null ? null : String.valueOf(v);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<String> piiPaths(Map<String, Object> fields) {
        Object v = fields.get("piiPaths");
        return v instanceof List ? (List<String>) v : List.of();
    }

    public static String lender(Map<String, Object> fields) {
        Object v = fields.get("lender");
        return v == null ? null : String.valueOf(v);
    }
}
