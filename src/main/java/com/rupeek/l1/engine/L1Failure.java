package com.rupeek.l1.engine;

import com.rupeek.l1.api.FailureDetails;
import com.rupeek.l1.ops.ErrorClass;
import io.temporal.failure.ApplicationFailure;

/**
 * A classified failure inside a recipe run. Converted to a Temporal {@link ApplicationFailure} at the activity
 * boundary: type = {@link com.rupeek.l1.api.L1FailureType}, retryable only for {@link ErrorClass#TECHNICAL}.
 * The message must already be PII-free; the original exception is not sent to Temporal.
 */
public class L1Failure extends RuntimeException {

    private final ErrorClass errorClass;
    private final String stepId;
    private final String opRef;
    private final Integer httpStatus;

    public L1Failure(ErrorClass errorClass, String message, String stepId, String opRef, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.errorClass = errorClass;
        this.stepId = stepId;
        this.opRef = opRef;
        this.httpStatus = httpStatus;
    }

    public static L1Failure permanent(String message, Throwable cause) {
        return new L1Failure(ErrorClass.PERMANENT, message, null, null, null, cause);
    }

    public ErrorClass errorClass() {
        return errorClass;
    }

    public String stepId() {
        return stepId;
    }

    public String opRef() {
        return opRef;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    /** Same failure attributed to a step, with a replacement (masked) message. */
    public L1Failure at(String step, String op, String maskedMessage) {
        return new L1Failure(errorClass, maskedMessage, step != null ? step : stepId, op != null ? op : opRef,
                httpStatus, getCause());
    }

    public ApplicationFailure toApplicationFailure(String recipeRef, int recipeVersion) {
        FailureDetails details = new FailureDetails(recipeRef, recipeVersion, stepId, opRef, errorClass.name(),
                httpStatus, getMessage());
        String type = errorClass.failureType().name();
        String message = errorClass.name() + " at " + recipeRef + "@" + recipeVersion
                + (stepId == null ? "" : " step " + stepId) + ": " + getMessage();
        return errorClass.failureType().retryable()
                ? ApplicationFailure.newFailure(message, type, details)
                : ApplicationFailure.newNonRetryableFailure(message, type, details);
    }
}
