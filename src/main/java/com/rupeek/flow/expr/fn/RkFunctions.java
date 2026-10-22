package com.rupeek.flow.expr.fn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rupeek.flow.expr.CelValues;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.types.CelType;
import dev.cel.common.types.SimpleType;
import dev.cel.runtime.CelFunctionBinding;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code rk.*} functions: generic helpers CEL lacks. No I/O, no lender knowledge, deterministic.
 *
 * <table>
 *   <tr><td>{@code rk.round(x, scale, mode)}</td><td>{@code BigDecimal.valueOf(x).setScale(scale, mode)}; parity with legacy rounding</td></tr>
 *   <tr><td>{@code rk.sum(list)}, {@code rk.sum(list, 'field')}</td><td>exact decimal sum; int if every item is an int</td></tr>
 *   <tr><td>{@code rk.groupSum(list, 'key', 'value')}</td><td>map key to sum, first-seen order</td></tr>
 *   <tr><td>{@code rk.groupCount(list, 'key')}</td><td>map key to count</td></tr>
 *   <tr><td>{@code rk.lookup(table, key, default)}</td><td>table value for key, else default</td></tr>
 *   <tr><td>{@code rk.json(x)}, {@code rk.parseJson(s)}</td><td>JSON to and from a string</td></tr>
 *   <tr><td>{@code rk.date.format(v, from, to)}, {@code rk.date.parse(v, from)}</td><td>date conversion; {@code 'ISO'} accepts ISO dates and date-times</td></tr>
 *   <tr><td>{@code rk.mask(v, keepLast)}</td><td>{@code XXXXXX1234}; null stays null</td></tr>
 *   <tr><td>{@code rk.nameSplit(customer, 'first'|'middle'|'last')}</td><td>legacy name rule (see method)</td></tr>
 *   <tr><td>{@code rk.uuidFrom(seed)}</td><td>name-based UUID v5; same seed, same id</td></tr>
 *   <tr><td>{@code rk.str(v)}, {@code rk.str(v, default)}</td><td>scalar as a string, null stays null (or
 *       {@code default}); mirrors a Java {@code String} DTO field and {@code Objects.toString(v, default)}</td></tr>
 * </table>
 */
public final class RkFunctions implements FunctionLibrary {

    public static final String VERSION = "2";

    /** Namespace for {@link #uuidFrom(String)} (RFC 4122 URL namespace). */
    private static final UUID UUID_NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final List<CelFunctionDecl> declarations = new ArrayList<>();
    /** Bindings per function name; grouped so the planner can dispatch on runtime types (e.g. a dyn argument). */
    private final Map<String, List<CelFunctionBinding>> bindings = new LinkedHashMap<>();

    public RkFunctions() {
        CelType dyn = SimpleType.DYN;
        CelType str = SimpleType.STRING;
        CelType i64 = SimpleType.INT;
        CelType f64 = SimpleType.DOUBLE;

        add("rk.round", "rk_round_double", SimpleType.DOUBLE, List.of(f64, i64, str), List.of(Double.class, Long.class, String.class),
                a -> round(((Double) a[0]), (Long) a[1], (String) a[2]));
        add("rk.round", "rk_round_int", SimpleType.DOUBLE, List.of(i64, i64, str), List.of(Long.class, Long.class, String.class),
                a -> round(((Long) a[0]).doubleValue(), (Long) a[1], (String) a[2]));

        add("rk.sum", "rk_sum_list", dyn, List.of(dyn), List.of(Object.class), a -> sum(list(a[0], "rk.sum"), null));
        add("rk.sum", "rk_sum_list_field", dyn, List.of(dyn, str), List.of(Object.class, String.class),
                a -> sum(list(a[0], "rk.sum"), (String) a[1]));
        add("rk.groupSum", "rk_groupSum", dyn, List.of(dyn, str, str), List.of(Object.class, String.class, String.class),
                a -> groupSum(list(a[0], "rk.groupSum"), (String) a[1], (String) a[2]));
        add("rk.groupCount", "rk_groupCount", dyn, List.of(dyn, str), List.of(Object.class, String.class),
                a -> groupCount(list(a[0], "rk.groupCount"), (String) a[1]));
        add("rk.lookup", "rk_lookup", dyn, List.of(dyn, str, dyn), List.of(Object.class, String.class, Object.class),
                a -> lookup(map(a[0], "rk.lookup"), (String) a[1], a[2]));

        add("rk.json", "rk_json", str, List.of(dyn), List.of(Object.class), a -> json(a[0]));
        add("rk.parseJson", "rk_parseJson", dyn, List.of(str), List.of(String.class), a -> parseJson((String) a[0]));

        add("rk.date.format", "rk_date_format", dyn, List.of(dyn, str, str), List.of(Object.class, String.class, String.class),
                a -> CelValues.isNull(a[0]) ? null : dateFormat(String.valueOf(a[0]), (String) a[1], (String) a[2]));
        add("rk.date.parse", "rk_date_parse", dyn, List.of(dyn, str), List.of(Object.class, String.class),
                a -> CelValues.isNull(a[0]) ? null : dateParse(String.valueOf(a[0]), (String) a[1]));

        add("rk.mask", "rk_mask", dyn, List.of(dyn, i64), List.of(Object.class, Long.class),
                a -> CelValues.isNull(a[0]) ? null : mask(String.valueOf(a[0]), (Long) a[1]));
        add("rk.nameSplit", "rk_nameSplit", dyn, List.of(dyn, str), List.of(Object.class, String.class),
                a -> nameSplit(map(a[0], "rk.nameSplit"), (String) a[1]));
        add("rk.uuidFrom", "rk_uuidFrom", str, List.of(str), List.of(String.class), a -> uuidFrom((String) a[0]));
        add("rk.str", "rk_str", dyn, List.of(dyn), List.of(Object.class), a -> str(a[0]));
        add("rk.str", "rk_str_default", dyn, List.of(dyn, str), List.of(Object.class, String.class),
                a -> { Object v = str(a[0]); return v == null ? a[1] : v; });
    }

    @Override
    public String name() {
        return "rk";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public List<CelFunctionDecl> declarations() {
        // Group overloads of the same function name into one declaration.
        Map<String, List<CelOverloadDecl>> byName = new LinkedHashMap<>();
        declarations.forEach(d -> byName.computeIfAbsent(d.name(), k -> new ArrayList<>()).addAll(d.overloads()));
        List<CelFunctionDecl> out = new ArrayList<>();
        byName.forEach((name, overloads) -> out.add(CelFunctionDecl.newFunctionDeclaration(name, overloads)));
        return out;
    }

    @Override
    public List<CelFunctionBinding> bindings() {
        List<CelFunctionBinding> out = new ArrayList<>();
        bindings.forEach((name, overloads) -> out.addAll(CelFunctionBinding.fromOverloads(name, overloads)));
        return out;
    }

    // ---------------------------------------------------------------- implementations (public for unit tests)

    /** Same arithmetic as legacy {@code roundOff}: {@code BigDecimal.valueOf(value).setScale(scale, mode)}. */
    public static double round(double value, long scale, String mode) {
        return BigDecimal.valueOf(value).setScale((int) scale, RoundingMode.valueOf(mode)).doubleValue();
    }

    public static Object sum(List<?> items, String field) {
        BigDecimal total = BigDecimal.ZERO;
        boolean allIntegers = true;
        for (Object item : items) {
            Object value = field == null ? item : map(item, "rk.sum").get(field);
            if (!(value instanceof Number n)) {
                throw new IllegalArgumentException("rk.sum: not a number: " + display(value));
            }
            allIntegers &= value instanceof Long || value instanceof Integer;
            total = total.add(n instanceof Double d ? BigDecimal.valueOf(d) : new BigDecimal(n.toString()));
        }
        return allIntegers ? (Object) total.longValueExact() : (Object) total.doubleValue();
    }

    public static Map<String, Object> groupSum(List<?> items, String keyField, String valueField) {
        Map<String, List<Object>> groups = new LinkedHashMap<>();
        for (Object item : items) {
            Map<?, ?> row = map(item, "rk.groupSum");
            groups.computeIfAbsent(String.valueOf(unwrap(row.get(keyField))), k -> new ArrayList<>())
                    .add(unwrap(row.get(valueField)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        groups.forEach((k, values) -> out.put(k, sum(values, null)));
        return out;
    }

    public static Map<String, Object> groupCount(List<?> items, String keyField) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Object item : items) {
            String key = String.valueOf(unwrap(map(item, "rk.groupCount").get(keyField)));
            out.merge(key, 1L, (a, b) -> (Long) a + (Long) b);
        }
        return out;
    }

    public static Object lookup(Map<?, ?> table, String key, Object fallback) {
        return table.containsKey(key) ? table.get(key) : fallback;
    }

    public static String json(Object value) {
        try {
            return JSON.writeValueAsString(CelValues.fromCel(value));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("rk.json: cannot serialise: " + e.getOriginalMessage(), e);
        }
    }

    public static Object parseJson(String text) {
        try {
            return JSON.readValue(text, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("rk.parseJson: not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Re-formats a date. {@code from} is a {@link DateTimeFormatter} pattern or {@code ISO} (ISO date, date-time
     * or offset date-time). Month and day names are English, as in legacy ({@code dd-MMM-yyyy} gives {@code 05-Jan-1990};
     * chain {@code .upperAscii()} for {@code 05-JAN-1990}).
     */
    public static String dateFormat(String value, String from, String to) {
        TemporalAccessor parsed = parseDate(value, from);
        return DateTimeFormatter.ofPattern(to, Locale.ENGLISH).format(parsed);
    }

    /** Parses with {@code from} and returns ISO: {@code yyyy-MM-dd}, or {@code yyyy-MM-ddTHH:mm:ss} if a time was present. */
    public static String dateParse(String value, String from) {
        TemporalAccessor parsed = parseDate(value, from);
        return parsed instanceof LocalDateTime dt ? dt.toString() : LocalDate.from(parsed).toString();
    }

    /** Keeps the last {@code keepLast} characters; masks everything if the value is that short or shorter. */
    public static String mask(String value, long keepLast) {
        int keep = (int) Math.max(0, keepLast);
        if (value.length() <= keep) {
            return "X".repeat(value.length());
        }
        return "X".repeat(value.length() - keep) + value.substring(value.length() - keep);
    }

    /**
     * Legacy Axis name rule ({@code AxisApiService.populateAxisPrecheckRequest}): split
     * {@code customerProofName} on single spaces, upper-cased.
     * 1 part: last = {@code "."}; 2: first/last; 3: first/middle/last; 4 or more: last = parts 3.. joined.
     * Blank proof name: raw {@code firstName} / {@code lastName}, not upper-cased, no middle.
     */
    public static Object nameSplit(Map<?, ?> customer, String part) {
        if (!List.of("first", "middle", "last").contains(part)) {
            throw new IllegalArgumentException("rk.nameSplit: part must be 'first', 'middle' or 'last', got '" + part + "'");
        }
        Object proof = unwrap(customer.get("customerProofName"));
        if (proof == null || proof.toString().isEmpty()) {
            return switch (part) {
                case "first" -> unwrap(customer.get("firstName"));
                case "last" -> unwrap(customer.get("lastName"));
                default -> null;
            };
        }
        String[] p = proof.toString().split(" ");
        if (p.length == 0) {
            throw new IllegalArgumentException("rk.nameSplit: customerProofName has no name parts");
        }
        String first = p[0].toUpperCase(Locale.ROOT);
        String middle;
        String last;
        switch (p.length) {
            case 1 -> { middle = null; last = "."; }
            case 2 -> { middle = null; last = p[1].toUpperCase(Locale.ROOT); }
            case 3 -> { middle = p[1].toUpperCase(Locale.ROOT); last = p[2].toUpperCase(Locale.ROOT); }
            default -> {
                middle = p[1].toUpperCase(Locale.ROOT);
                last = String.join(" ", Arrays.copyOfRange(p, 2, p.length)).toUpperCase(Locale.ROOT);
            }
        }
        return switch (part) {
            case "first" -> first;
            case "middle" -> middle;
            default -> last;
        };
    }

    /**
     * A scalar as a string; null stays null. Whole numbers have no decimal part ({@code 560001}, not
     * {@code 560001.0}), matching how Jackson fills a {@code String} field from a JSON integer.
     */
    public static Object str(Object value) {
        Object v = unwrap(value);
        if (v == null || v instanceof String) {
            return v;
        }
        if (v instanceof Double d) {
            return d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15
                    ? Long.toString(d.longValue()) : Double.toString(d);
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        throw new IllegalArgumentException("rk.str: expected a scalar, got " + v.getClass().getSimpleName());
    }

    /** Name-based UUID version 5 (SHA-1) of {@code seed}: retries that use the same seed send the same id. */
    public static String uuidFrom(String seed) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(toBytes(UUID_NAMESPACE));
            byte[] hash = sha1.digest(seed.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50); // version 5
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // RFC 4122 variant
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (hash[i] & 0xff);
            }
            for (int i = 8; i < 16; i++) {
                lsb = (lsb << 8) | (hash[i] & 0xff);
            }
            return new UUID(msb, lsb).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void add(String name, String overloadId, CelType result, List<CelType> params, List<Class<?>> javaTypes,
                     Impl impl) {
        declarations.add(CelFunctionDecl.newFunctionDeclaration(name,
                CelOverloadDecl.newGlobalOverload(overloadId, result, params)));
        bindings.computeIfAbsent(name, k -> new ArrayList<>())
                .add(CelFunctionBinding.from(overloadId, javaTypes, args -> CelValues.toCel(impl.apply(args))));
    }

    @FunctionalInterface
    private interface Impl {
        Object apply(Object[] args);
    }

    private static TemporalAccessor parseDate(String value, String from) {
        try {
            if ("ISO".equals(from)) {
                if (value.length() <= 10) {
                    return LocalDate.parse(value);
                }
                try {
                    return LocalDateTime.parse(value);
                } catch (DateTimeParseException e) {
                    return OffsetDateTime.parse(value).toLocalDateTime();
                }
            }
            DateTimeFormatter f = DateTimeFormatter.ofPattern(from, Locale.ENGLISH);
            try {
                return LocalDateTime.parse(value, f);
            } catch (DateTimeParseException e) {
                return LocalDate.parse(value, f);
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("rk.date: '" + value + "' does not match '" + from + "'", e);
        }
    }

    private static List<?> list(Object value, String fn) {
        if (value instanceof List<?> l) {
            return l;
        }
        throw new IllegalArgumentException(fn + ": expected a list but got " + display(value));
    }

    private static Map<?, ?> map(Object value, String fn) {
        if (value instanceof Map<?, ?> m) {
            return m;
        }
        throw new IllegalArgumentException(fn + ": expected a map but got " + display(value));
    }

    private static Object unwrap(Object value) {
        return CelValues.isNull(value) ? null : value;
    }

    private static String display(Object value) {
        return CelValues.isNull(value) ? "null" : value.getClass().getSimpleName();
    }

    private static byte[] toBytes(UUID uuid) {
        byte[] out = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (msb >>> (56 - 8 * i));
            out[8 + i] = (byte) (lsb >>> (56 - 8 * i));
        }
        return out;
    }
}
