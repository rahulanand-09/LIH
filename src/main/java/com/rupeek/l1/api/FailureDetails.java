package com.rupeek.l1.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Details attached to every L1 {@code ApplicationFailure}. {@code message} is PII-masked.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FailureDetails(
        String recipeRef,
        int recipeVersion,
        String stepId,
        String opRef,
        String errorClass,
        Integer httpStatus,
        String message) {
}
