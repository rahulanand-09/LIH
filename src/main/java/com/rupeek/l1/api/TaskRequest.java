package com.rupeek.l1.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Input to {@link TaskActivity#runTask}. Recorded in Temporal history, so it carries ids and event
 * payloads only, never customer PII.
 *
 * @param recipeRef     recipe reference, e.g. {@code axis.precheck.dedupe}
 * @param recipeVersion version pinned by L2 at case start
 * @param caseId        Temporal workflow id of the case
 * @param runNo         run number; increments only on revert
 * @param inputs        recipe inputs, e.g. {@code loanRequestId}; callbacks pass their payload as {@code event}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskRequest(
        String recipeRef,
        int recipeVersion,
        String caseId,
        int runNo,
        Map<String, Object> inputs) {
}
