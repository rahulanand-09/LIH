package com.rupeek.l1.intercept;

import com.rupeek.l1.expr.fn.RkFunctions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks PII in audit payloads, logs and failure messages. Masked values keep their last 4 characters
 * ({@code XXXXXX1234}).
 *
 * <p>Masked: any key in {@link #GLOBAL_KEYS} (case-insensitive, at any depth, including everything beneath it),
 * plus the operation's {@code piiPaths} ({@code $.a.b}, {@code $.list[*].x}, {@code $.list[0].x}).
 */
public final class PiiMasker {

    public static final Set<String> GLOBAL_KEYS = Set.of(
            "pan", "aadhar", "aadhaar", "aadhar_number", "mobile_number", "customerphone", "phone", "email",
            "dob", "date_of_birth", "date_of_birth_as_per_pan", "address_value", "id_value");

    private static final int KEEP_LAST = 4;
    private static final int MIN_TEXT_VALUE_LENGTH = 6;
    private static final Pattern SEGMENT = Pattern.compile("([^\\[]+)((?:\\[(?:\\*|\\d+)])*)");
    private static final Pattern INDEX = Pattern.compile("\\[(\\*|\\d+)]");

    /** Returns a masked deep copy; the input is not modified. */
    public Object maskPayload(Object payload, List<String> piiPaths) {
        Object copy = maskGlobalKeys(payload);
        for (String path : piiPaths) {
            copy = maskPath(copy, parse(path), 0);
        }
        return copy;
    }

    /** Replaces every occurrence of the given sensitive values (6+ characters) in free text. */
    public String maskText(String text, Collection<String> sensitiveValues) {
        if (text == null) {
            return null;
        }
        String out = text;
        List<String> values = new ArrayList<>(sensitiveValues);
        values.sort(Comparator.comparingInt(String::length).reversed());
        for (String v : values) {
            if (v != null && v.length() >= MIN_TEXT_VALUE_LENGTH) {
                out = out.replace(v, maskScalar(v).toString());
            }
        }
        return out;
    }

    /**
     * Values that must never leave the worker: strings under a {@link #GLOBAL_KEYS} key at any depth, and the value
     * itself when it is a plain string declared PII ({@code wholeValueIsPii}). Used by the engine's output guard.
     */
    public static void collectPiiValues(Object value, boolean wholeValueIsPii, Collection<String> into) {
        if (value instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (GLOBAL_KEYS.contains(String.valueOf(k).toLowerCase(Locale.ROOT))) {
                    collectStrings(v, into);
                } else {
                    collectPiiValues(v, false, into);
                }
            });
        } else if (value instanceof List<?> l) {
            l.forEach(v -> collectPiiValues(v, false, into));
        } else if (value instanceof String s && wholeValueIsPii) {
            into.add(s);
        }
    }

    /** All string leaves of a value, for {@link #maskText}. */
    public static void collectStrings(Object value, Collection<String> into) {
        if (value instanceof Map<?, ?> m) {
            m.values().forEach(v -> collectStrings(v, into));
        } else if (value instanceof List<?> l) {
            l.forEach(v -> collectStrings(v, into));
        } else if (value instanceof String s) {
            into.add(s);
        }
    }

    private Object maskGlobalKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                out.put(key, GLOBAL_KEYS.contains(key.toLowerCase(Locale.ROOT)) ? maskAll(v) : maskGlobalKeys(v));
            });
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(maskGlobalKeys(v)));
            return out;
        }
        return value;
    }

    private Object maskPath(Object node, List<String[]> path, int depth) {
        if (depth == path.size()) {
            return maskAll(node);
        }
        String[] seg = path.get(depth);
        String key = seg[0];
        if (!(node instanceof Map<?, ?> map) || !map.containsKey(key)) {
            return node;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) map;
        Object child = m.get(key);
        child = seg.length == 1 ? maskPath(child, path, depth + 1) : maskIndexed(child, seg, 1, path, depth);
        m.put(key, child);
        return m;
    }

    private Object maskIndexed(Object node, String[] seg, int i, List<String[]> path, int depth) {
        if (i == seg.length) {
            return maskPath(node, path, depth + 1);
        }
        if (!(node instanceof List<?> list)) {
            return node;
        }
        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>) list;
        if (seg[i].equals("*")) {
            for (int j = 0; j < l.size(); j++) {
                l.set(j, maskIndexed(l.get(j), seg, i + 1, path, depth));
            }
        } else {
            int idx = Integer.parseInt(seg[i]);
            if (idx < l.size()) {
                l.set(idx, maskIndexed(l.get(idx), seg, i + 1, path, depth));
            }
        }
        return l;
    }

    private static Object maskAll(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), maskAll(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(maskAll(v)));
            return out;
        }
        return maskScalar(value);
    }

    private static Object maskScalar(Object value) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        return RkFunctions.mask(String.valueOf(value), KEEP_LAST);
    }

    /** {@code $.a.b[*].c} into segments {@code [a]}, {@code [b, *]}, {@code [c]}. */
    private static List<String[]> parse(String path) {
        String p = path.startsWith("$.") ? path.substring(2) : path.startsWith("$") ? path.substring(1) : path;
        List<String[]> out = new ArrayList<>();
        for (String part : p.split("\\.")) {
            Matcher m = SEGMENT.matcher(part);
            if (!m.matches()) {
                throw new IllegalArgumentException("invalid PII path: " + path);
            }
            List<String> seg = new ArrayList<>();
            seg.add(m.group(1));
            Matcher idx = INDEX.matcher(m.group(2));
            while (idx.find()) {
                seg.add(idx.group(1));
            }
            out.add(seg.toArray(String[]::new));
        }
        return out;
    }
}
