package com.rupeek.l1.expr;

/**
 * Hard limits applied to every expression, checked at compile time (length, depth) and at runtime
 * (iterations of list macros such as {@code map}, {@code filter}, {@code all}).
 */
public record ExprLimits(int maxExpressionLength, int maxParseDepth, int maxIterations) {

    public static final ExprLimits DEFAULT = new ExprLimits(2000, 32, 1000);
}
