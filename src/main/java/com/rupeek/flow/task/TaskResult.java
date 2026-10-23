package com.rupeek.flow.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Result of {@link TaskActivity#execute}. Recorded in Temporal history.
 *
 * @param outcome one of this task's declared outcomes; the graph routes on it
 * @param outputs declared, non-PII outputs
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskResult(String outcome, Map<String, Object> outputs) {
}
