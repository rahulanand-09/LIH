package com.rupeek.l1.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a recipe returns: a literal outcome label and outputs built by CEL expressions.
 */
public record ReturnSpec(String outcome, Map<String, String> out) {

    public ReturnSpec {
        out = out == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(out));
    }
}
