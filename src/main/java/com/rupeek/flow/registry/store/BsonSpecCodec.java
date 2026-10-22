package com.rupeek.flow.registry.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a parsed config tree (plain {@code Map}/{@code List}/scalars, from {@code ObjectMapper.readValue(json,
 * Object.class)}) into a native {@link Document} Mongo can store as a real, expandable subdocument — and back.
 *
 * <p>Two config-language features produce field names Mongo itself rejects on insert: {@code TemplateEvaluator}'s
 * reserved {@code $each}/{@code $let} directive keys (a leading {@code $} is reserved for operators), and
 * {@code MongoQueryBinder}'s dotted update paths like {@code "responseMap.pan"} (a {@code .} is reserved for path
 * traversal). Every {@code $} and {@code .} in a key is escaped to a fullwidth lookalike (U+FF04, U+FF0E) —
 * visually near-identical, byte-distinct, and reversible — so the document round-trips exactly through Mongo
 * while still being a real, browsable tree instead of an opaque string blob.
 */
final class BsonSpecCodec {

    private static final String DOLLAR_ESCAPE = "＄";
    private static final String DOT_ESCAPE = "．";
    private static final ObjectMapper READER = new ObjectMapper();

    private BsonSpecCodec() {
    }

    /** Parses {@code specJson} (as {@link FlowConfigCodec#json} produced it) and escapes it for safe storage. */
    static Document toBsonSafe(String specJson) {
        try {
            return (Document) escape(READER.readValue(specJson, Object.class));
        } catch (Exception e) {
            throw new FlowConfigException("cannot build a display view of this spec: " + e.getMessage(), e);
        }
    }

    static Object fromBsonSafe(Object bsonValue) {
        if (bsonValue instanceof Document doc) {
            Map<String, Object> out = new LinkedHashMap<>();
            doc.forEach((k, v) -> out.put(unescapeKey(k), fromBsonSafe(v)));
            return out;
        }
        if (bsonValue instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(fromBsonSafe(v)));
            return out;
        }
        return bsonValue;
    }

    private static Object escape(Object value) {
        if (value instanceof Map<?, ?> map) {
            Document out = new Document();
            map.forEach((k, v) -> out.put(escapeKey(String.valueOf(k)), escape(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(escape(v)));
            return out;
        }
        return value;
    }

    private static String escapeKey(String key) {
        return key.replace("$", DOLLAR_ESCAPE).replace(".", DOT_ESCAPE);
    }

    private static String unescapeKey(String key) {
        return key.replace(DOLLAR_ESCAPE, "$").replace(DOT_ESCAPE, ".");
    }
}
