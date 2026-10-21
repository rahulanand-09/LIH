package com.rupeek.l1.expr;

import dev.cel.common.values.NullValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Converts between plain Java data and CEL runtime values.
 *
 * <p>Inbound, CEL 0.14 throws a NullPointerException on a Java {@code null} anywhere inside a variable,
 * and treats {@code int} as {@code Long}; so nulls become {@link NullValue#NULL_VALUE} and small integer types
 * widen to {@code Long}. Only plain data is accepted: any other Java object is rejected, so config can never
 * reach application objects (rule: CEL sees only plain data).
 *
 * <p>Outbound, CEL null and optionals are unwrapped and collections become mutable Java collections.
 */
public final class CelValues {

    private CelValues() {
    }

    public static Object toCel(Object value) {
        if (value == null) {
            return NullValue.NULL_VALUE;
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Long || value instanceof Double) {
            return value;
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float f) {
            return f.doubleValue();
        }
        if (value instanceof BigDecimal d) {
            return d.doubleValue();
        }
        if (value instanceof BigInteger i) {
            return i.bitLength() < 64 ? (Object) i.longValue() : (Object) i.doubleValue();
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>(map.size() * 2);
            map.forEach((k, v) -> out.put(k instanceof String ? k : String.valueOf(k), toCel(v)));
            return out;
        }
        if (value instanceof Iterable<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(toCel(v)));
            return out;
        }
        if (value instanceof Object[] array) {
            List<Object> out = new ArrayList<>(array.length);
            for (Object v : array) {
                out.add(toCel(v));
            }
            return out;
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof TemporalAccessor t) {
            return t.toString();
        }
        if (isCelNative(value)) {
            return value;
        }
        throw new IllegalArgumentException("only plain data may be passed to expressions, got "
                + value.getClass().getName());
    }

    public static Object fromCel(Object value) {
        if (value == null || value instanceof NullValue || value instanceof com.google.protobuf.NullValue) {
            return null;
        }
        if (value instanceof Optional<?> opt) {
            return opt.map(CelValues::fromCel).orElse(null);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>(map.size() * 2);
            map.forEach((k, v) -> out.put(String.valueOf(k), fromCel(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(fromCel(v)));
            return out;
        }
        return value;
    }

    /** True for CEL's own null; lets functions treat a CEL null argument as Java null. */
    public static boolean isNull(Object value) {
        return value == null || value instanceof NullValue || value instanceof com.google.protobuf.NullValue;
    }

    private static boolean isCelNative(Object value) {
        String name = value.getClass().getName();
        return name.startsWith("dev.cel.") || value instanceof com.google.protobuf.ByteString;
    }
}
