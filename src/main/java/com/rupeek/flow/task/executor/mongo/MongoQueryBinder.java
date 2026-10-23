package com.rupeek.flow.task.executor.mongo;

import com.rupeek.flow.task.FlowTaskFailure;
import com.rupeek.flow.task.TaskFailureType;
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
 * Validates and binds fixed-shape Mongo documents from a node's {@code fields}. Flow-native port of
 * {@code com.rupeek.l1.ops.mongo.MongoQueryBinder} — identical placeholder scheme, NOT CEL: a string value
 * {@code ":name"} is a placeholder bound from the node's {@code inputs}, or from {@code sys.now}/{@code
 * sys.caseId}/{@code sys.runNo}. {@code "::x"} is the literal string {@code ":x"}. Field names and operators are
 * never dynamic: config cannot build query text from data. {@code $where}/{@code $function}/{@code
 * $accumulator}/{@code $expr} are hard-blocked everywhere, by design — this is a security boundary, not a gap.
 */
public final class MongoQueryBinder {

    public static final Set<String> FORBIDDEN = Set.of("$where", "$function", "$accumulator", "$expr");
    private static final Set<String> SYS_VALUES = Set.of("now", "caseId", "runNo");

    private MongoQueryBinder() {
    }

    /**
     * Checks a document from config. {@code operatorsAllowed} is true for filters (query operators such as
     * {@code $in}); false for update parts, where every key is a field name.
     */
    public static void validate(Map<String, Object> doc, String where, boolean operatorsAllowed) {
        if (doc == null) {
            return;
        }
        walk(doc, where, operatorsAllowed);
    }

    private static void walk(Object value, String where, boolean operatorsAllowed) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (FORBIDDEN.contains(key)) {
                    throw new FlowTaskFailure(TaskFailureType.PERMANENT, where + ": operator " + key + " is not allowed");
                }
                if (key.startsWith("$") && !operatorsAllowed) {
                    throw new FlowTaskFailure(TaskFailureType.PERMANENT,
                            where + ": '" + key + "' is not allowed here (field names only)");
                }
                walk(e.getValue(), where + "." + key, operatorsAllowed);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                walk(list.get(i), where + "[" + i + "]", operatorsAllowed);
            }
        }
    }

    /** Returns a copy with placeholders replaced by values. Times become BSON dates, like Spring Data writes. */
    public static Document bind(Map<String, Object> doc, Map<String, Object> inputs, Map<String, Object> sys) {
        Document out = new Document();
        if (doc != null) {
            doc.forEach((k, v) -> out.put(k, bindValue(v, inputs, sys)));
        }
        return out;
    }

    private static Object bindValue(Object value, Map<String, Object> inputs, Map<String, Object> sys) {
        if (value instanceof Map<?, ?> map) {
            Document out = new Document();
            map.forEach((k, v) -> out.put(String.valueOf(k), bindValue(v, inputs, sys)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(bindValue(v, inputs, sys)));
            return out;
        }
        if (value instanceof String s && s.startsWith("::")) {
            return s.substring(1);
        }
        if (value instanceof String s && s.startsWith(":")) {
            String name = s.substring(1);
            Object bound = name.startsWith("sys.")
                    ? (SYS_VALUES.contains(name.substring(4)) ? sys.get(name.substring(4)) : null)
                    : inputs.get(name);
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
