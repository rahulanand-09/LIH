package com.rupeek.l1.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One recipe step. Either runs an operation ({@code op}) or is an early-return step ({@code return}).
 *
 * @param id      unique in the recipe; later steps read {@code steps.<id>.<output>}
 * @param op      {@code ref@version} of the operation to run; null on a return step
 * @param when    CEL guard; the step is skipped when it evaluates to false
 * @param in      operation parameter name to CEL expression
 * @param onError error class name to {@code raise} or {@code return:<OUTCOME>}; default {@code raise}
 */
public record Step(
        String id,
        String op,
        String when,
        Map<String, String> in,
        @JsonProperty("return") ReturnSpec returnSpec,
        Map<String, String> onError) {

    public Step {
        in = in == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(in));
        onError = onError == null ? Map.of() : Map.copyOf(onError);
    }

    public boolean isReturn() {
        return returnSpec != null;
    }
}
