package com.rupeek.l1.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rupeek.l1.model.ParamType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks what a recipe returns to L2 before it is written to Temporal history: only declared outputs, of the
 * declared types (null allowed), at most {@value #MAX_BYTES} bytes as JSON, and containing no value that was
 * passed to a {@code pii: true} parameter during the run. Any violation is {@code PERMANENT}.
 */
public final class OutputGuard {

    public static final int MAX_BYTES = 16 * 1024;
    private static final int MIN_PII_LENGTH = 6;
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    public void check(Map<String, Object> outputs, CompiledRecipe recipe, Set<String> piiValues) {
        for (Map.Entry<String, Object> e : outputs.entrySet()) {
            ParamType type = recipe.outputs().get(e.getKey());
            if (type == null) {
                throw L1Failure.permanent("output '" + e.getKey() + "' is not declared", null);
            }
            if (!matches(type, e.getValue())) {
                throw L1Failure.permanent("output '" + e.getKey() + "' is not a " + type.configName(), null);
            }
            if (containsPii(e.getValue(), piiValues)) {
                throw L1Failure.permanent("output '" + e.getKey() + "' contains a value marked as PII", null);
            }
        }
        try {
            int size = JSON.writeValueAsBytes(outputs).length;
            if (size > MAX_BYTES) {
                throw L1Failure.permanent("outputs are " + size + " bytes; the limit is " + MAX_BYTES, null);
            }
        } catch (JsonProcessingException e) {
            throw L1Failure.permanent("outputs are not serialisable", e);
        }
    }

    static boolean matches(ParamType type, Object value) {
        if (value == null) {
            return true;
        }
        return switch (type) {
            case STRING -> value instanceof String;
            case INT -> value instanceof Long || value instanceof Integer;
            case DOUBLE -> value instanceof Number;
            case BOOL -> value instanceof Boolean;
            case MAP -> value instanceof Map;
            case LIST -> value instanceof List;
            case DYN -> true;
        };
    }

    private static boolean containsPii(Object value, Set<String> piiValues) {
        if (value instanceof String s) {
            return s.length() >= MIN_PII_LENGTH && piiValues.contains(s);
        }
        if (value instanceof Map<?, ?> m) {
            return m.values().stream().anyMatch(v -> containsPii(v, piiValues));
        }
        if (value instanceof List<?> l) {
            return l.stream().anyMatch(v -> containsPii(v, piiValues));
        }
        return false;
    }
}
