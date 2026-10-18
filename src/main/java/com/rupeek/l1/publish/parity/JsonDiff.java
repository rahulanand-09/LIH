package com.rupeek.l1.publish.parity;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Field-by-field difference between two JSON documents. Object key order is ignored (it is not part of the
 * contract); array order is not. Numbers compare by value. A missing key and an explicit null are different,
 * because a lender may treat them differently.
 */
public final class JsonDiff {

    /** @param path e.g. {@code address[1].address_city}; {@code legacy}/{@code ours} null when the key is absent */
    public record Diff(String path, JsonNode legacy, JsonNode ours) {
    }

    private JsonDiff() {
    }

    /** @param ignore paths to skip, with {@code [*]} for any index, e.g. {@code client_loan_id}, {@code ids[*].ts} */
    public static List<Diff> diff(JsonNode legacy, JsonNode ours, Set<String> ignore) {
        List<Diff> out = new ArrayList<>();
        walk("", legacy, ours, ignore, out);
        return out;
    }

    private static void walk(String path, JsonNode a, JsonNode b, Set<String> ignore, List<Diff> out) {
        if (!path.isEmpty() && ignored(path, ignore)) {
            return;
        }
        if (a != null && b != null && a.isObject() && b.isObject()) {
            Set<String> keys = new LinkedHashSet<>();
            a.fieldNames().forEachRemaining(keys::add);
            for (Iterator<String> it = b.fieldNames(); it.hasNext(); ) {
                keys.add(it.next());
            }
            for (String k : keys) {
                walk(path.isEmpty() ? k : path + "." + k, a.get(k), b.get(k), ignore, out);
            }
            return;
        }
        if (a != null && b != null && a.isArray() && b.isArray()) {
            for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
                walk(path + "[" + i + "]", a.get(i), b.get(i), ignore, out);
            }
            return;
        }
        if (!same(a, b)) {
            out.add(new Diff(path.isEmpty() ? "$" : path, a, b));
        }
    }

    private static boolean same(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        return a.equals(b);
    }

    private static boolean ignored(String path, Set<String> ignore) {
        String wildcard = path.replaceAll("\\[\\d+]", "[*]");
        return ignore.contains(path) || ignore.contains(wildcard);
    }
}
