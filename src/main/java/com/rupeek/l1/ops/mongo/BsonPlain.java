package com.rupeek.l1.ops.mongo;

import org.bson.BsonValue;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts Mongo results to plain data for expressions: ids become strings, dates ISO-8601 strings. */
final class BsonPlain {

    private BsonPlain() {
    }

    static Object toPlain(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), toPlain(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(toPlain(v)));
            return out;
        }
        if (value instanceof ObjectId id) {
            return id.toHexString();
        }
        if (value instanceof Date d) {
            return d.toInstant().toString();
        }
        if (value instanceof Decimal128 d) {
            return d.bigDecimalValue().doubleValue();
        }
        if (value instanceof BsonValue b) {
            if (b.isObjectId()) {
                return b.asObjectId().getValue().toHexString();
            }
            if (b.isString()) {
                return b.asString().getValue();
            }
            if (b.isInt32() || b.isInt64()) {
                return b.asNumber().longValue();
            }
            return b.toString();
        }
        return value;
    }
}
