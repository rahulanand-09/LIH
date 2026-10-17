package com.rupeek.l1.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Result of {@link TaskActivity#runTask}. Recorded in Temporal history.
 *
 * @param outcome       one of the recipe's declared outcomes; L2 routes on it
 * @param outputs       declared, non-PII outputs (bounded in size)
 * @param recipeRef     recipe that ran
 * @param recipeVersion version that ran
 * @param steps         per-step trace, for visibility in history
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskResult(
        String outcome,
        Map<String, Object> outputs,
        String recipeRef,
        int recipeVersion,
        List<StepTrace> steps) {
}
