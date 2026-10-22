package com.rupeek.flow.activity;

/**
 * A native executor failed with a known classification. Thrown across the executor boundary, caught by
 * {@link TaskActivityImpl}, and surfaced as a Temporal {@code ApplicationFailure} typed by {@link #type()} —
 * flow-native counterpart of {@code com.rupeek.l1.ops.OperationException} (and the role
 * {@code com.rupeek.l1.ops.TransportException} played, collapsed into one type since both ultimately just pick
 * a {@link TaskFailureType} and a message).
 */
public final class FlowTaskFailure extends RuntimeException {

    private final TaskFailureType type;
    private final Integer httpStatus;

    public FlowTaskFailure(TaskFailureType type, String message) {
        this(type, message, null, null);
    }

    public FlowTaskFailure(TaskFailureType type, String message, Throwable cause) {
        this(type, message, null, cause);
    }

    public FlowTaskFailure(TaskFailureType type, String message, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.type = type;
        this.httpStatus = httpStatus;
    }

    public TaskFailureType type() {
        return type;
    }

    public Integer httpStatus() {
        return httpStatus;
    }
}
