package com.rupeek.l1.ops.mongo;

import com.rupeek.l1.model.ConfigJson.ConfigException;
import org.bson.Document;

import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates and binds fixed-shape Mongo documents from config.
 *
 * <p>A string value {@code ":name"} is a placeholder bound from the operation's parameters, or from the
 * engine's {@code sys} values when written {@code ":sys.now"}, {@code ":sys.caseId"}, {@code ":sys.runNo"}.
 * {@code "::x"} is the literal {@code ":x"}. Field names and operators are never dynamic: config cannot build
 * query text from data.
 */
final class MongoQueryBinder {

    static final Set<String> FORBIDDEN = Set.of("$where", "$function", "$accumulator", "$expr");
    static final Set<String> SYS_VALUES = Set.of("now", "caseId", "runNo");

    private MongoQueryBinder() {
    }

    /**
     * Checks a document from config. {@code operatorsAllowed} is true for filters (query operators such as
     * {@code $in}); false for update parts, where every key is a field name.
     */
    static void validate(Map<String, Object> doc, String where, boolean operatorsAllowed, Set<String> params) {
        if (doc == null) {
            return;
        }
        walk(doc, where, operatorsAllowed, params);
    }

    private static void walk(Object value, String where, boolean operatorsAllowed, Set<String> params) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (FORBIDDEN.contains(key)) {
                    throw new ConfigException(where + ": operator " + key + " is not allowed");
                }
                if (key.startsWith("$") && !operatorsAllowed) {
                    throw new ConfigException(where + ": '" + key + "' is not allowed here (field names only)");
                }
                walk(e.getValue(), where + "." + key, operatorsAllowed, params);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                walk(list.get(i), where + "[" + i + "]", operatorsAllowed, params);
            }
        } else if (value instanceof String s && s.startsWith(":") && !s.startsWith("::")) {
            String name = s.substring(1);
            if (name.startsWith("sys.")) {
                if (!SYS_VALUES.contains(name.substring(4))) {
                    throw new ConfigException(where + ": unknown placeholder " + s + " (sys values: " + SYS_VALUES + ")");
                }
            } else if (!params.contains(name)) {
                throw new ConfigException(where + ": placeholder " + s + " is not a declared parameter");
            }
        }
    }

    /** Returns a copy with placeholders replaced by values. Times become BSON dates, like Spring Data writes. */
    static Document bind(Map<String, Object> doc, Map<String, Object> params, Map<String, Object> sys) {
        Document out = new Document();
        if (doc != null) {
            doc.forEach((k, v) -> out.put(k, bindValue(v, params, sys)));
        }
        return out;
    }

    private static Object bindValue(Object value, Map<String, Object> params, Map<String, Object> sys) {
        if (value instanceof Map<?, ?> map) {
            Document out = new Document();
            map.forEach((k, v) -> out.put(String.valueOf(k), bindValue(v, params, sys)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(bindValue(v, params, sys)));
            return out;
        }
        if (value instanceof String s && s.startsWith("::")) {
            return s.substring(1);
        }
        if (value instanceof String s && s.startsWith(":")) {
            String name = s.substring(1);
            Object bound = name.startsWith("sys.") ? sys.get(name.substring(4)) : params.get(name);
            return toBson(bound);
        }
        return value;
    }

    private static Object toBson(Object value) {
        if (value instanceof Instant i) {
            return Date.from(i);
        }
        if (value instanceof TemporalAccessor t && !(value instanceof Instant)) {
            return t.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), toBson(v)));
            return new Document(out);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(toBson(v)));
            return out;
        }
        return value;
    }
}
