package com.rupeek.flow.model;

import java.util.Map;

/** What a node leaves behind once it completes: the outcome label its outgoing edge routes on, plus outputs. */
public record NodeResult(String outcome, Map<String, Object> outputs) {
}
