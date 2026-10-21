package com.rupeek.l1.ops;

/**
 * An operation failed with a known class (e.g. a Mongo {@code expect} violation, an unparseable response,
 * a missing secret). Transport failures whose class depends on idempotency use {@link TransportException}.
 */
public class OperationException extends RuntimeException {

    private final ErrorClass errorClass;
    private final Integer httpStatus;

    public OperationException(ErrorClass errorClass, String message) {
        this(errorClass, message, null, null);
    }

    public OperationException(ErrorClass errorClass, String message, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.errorClass = errorClass;
        this.httpStatus = httpStatus;
    }

    public ErrorClass errorClass() {
        return errorClass;
    }

    public Integer httpStatus() {
        return httpStatus;
    }
}
