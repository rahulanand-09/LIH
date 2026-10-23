package com.rupeek.flow.expr;

/**
 * Hard limits applied to every expression, checked at compile time (length, depth) and at runtime
 * (iterations of list macros such as {@code map}, {@code filter}, {@code all}).
 */
public record ExprLimits(int maxExpressionLength, int maxParseDepth, int maxIterations) {

    /**
     * {@code maxExpressionLength} is 4000 here, double L1's original 2000 — flow collapses what used to be a
     * recipe step's {@code in} expression plus a separate operation's own template/out expression into one
     * single-node CEL expression (no more two-layer config, see the plan's decision #3), so a legitimately
     * large template (e.g. a full customer-profile projection) can exceed L1's per-step budget in one shot even
     * though it does no more actual work. This is a complexity guard, not a security boundary (flow config is
     * author-trusted, same as L1's was) — doubling it is a deliberate, low-risk accommodation for that
     * structural change, not a loosened safety rule.
     */
    public static final ExprLimits DEFAULT = new ExprLimits(4000, 32, 1000);
}
