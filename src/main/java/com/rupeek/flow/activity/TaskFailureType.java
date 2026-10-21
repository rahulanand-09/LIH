package com.rupeek.flow.activity;

/**
 * Thrown as the {@code type} of a Temporal {@code ApplicationFailure}. Only {@link #retryable} drives engine
 * behaviour (retry vs. park); the specific name is for operator-facing display only. A legitimate business
 * rejection is never one of these — it's a normal {@link TaskResult} with a named outcome.
 */
public enum TaskFailureType {
    TECHNICAL(true),
    UNKNOWN(false),
    BUSINESS(false),
    PERMANENT(false);

    public final boolean retryable;

    TaskFailureType(boolean retryable) {
        this.retryable = retryable;
    }
}
