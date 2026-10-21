package com.rupeek.l1.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One executed (or skipped) recipe step.
 *
 * @param status {@code OK}, {@code SKIPPED}, {@code RETURNED}, or the error class name
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StepTrace(String stepId, String opRef, String status, long durationMs) {
}
