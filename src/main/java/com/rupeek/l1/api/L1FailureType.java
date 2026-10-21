package com.rupeek.l1.api;

/**
 * Temporal {@code ApplicationFailure} types raised by L1. Only {@link #L1_TECHNICAL} is retryable;
 * L2 lists the other three as non-retryable in its activity options.
 */
public enum L1FailureType {
    /** Network error, 5xx/429 on idempotent calls, timeout before sending, transient Mongo error. */
    L1_TECHNICAL(true),
    /** The lender said no and the recipe raised instead of returning an outcome. */
    L1_BUSINESS(false),
    /** A non-idempotent call may have reached the lender (timeout or 5xx after sending). */
    L1_UNKNOWN(false),
    /** Config or data error: CEL failure, missing required output, input mismatch, unknown operation. */
    L1_PERMANENT(false);

    private final boolean retryable;

    L1FailureType(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
