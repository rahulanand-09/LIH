package com.rupeek.l1.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How one operation runs. Common fields apply to every kind; the rest are read only by the matching
 * executor, which validates them at publish time.
 *
 * <p>Common: {@code op}, {@code version}, {@code kind}, {@code resource}, {@code params}, {@code out} (CEL),
 * {@code required}, {@code timeoutMs}, {@code idempotent}, {@code audit} (literal or {@code =CEL}),
 * {@code piiPaths}.
 * <br>HTTP: {@code request}, {@code success} (CEL), {@code classify} (CEL returning an error class name).
 * <br>MONGO: {@code collection}, {@code action}, {@code filter}, {@code set}, {@code setOnInsert}, {@code inc},
 * {@code sort}, {@code projection}, {@code limit}, {@code expect}. Values written {@code ":name"} bind from
 * params or {@code sys}; shapes are fixed.
 * <br>MAP: {@code template}.
 * <br>GEN: {@code action} ({@code STABLE_ID}, {@code NOW}).
 */
public record OperationDef(
        String op,
        Integer version,
        OperationKind kind,
        String resource,
        Map<String, ParamSpec> params,
        Map<String, String> out,
        List<String> required,
        Integer timeoutMs,
        boolean idempotent,
        String audit,
        List<String> piiPaths,
        // HTTP
        HttpRequestSpec request,
        String success,
        String classify,
        // MONGO
        String collection,
        String action,
        Map<String, Object> filter,
        Map<String, Object> set,
        Map<String, Object> setOnInsert,
        Map<String, Object> inc,
        Map<String, Object> sort,
        Map<String, Object> projection,
        Integer limit,
        Map<String, Object> expect,
        // MAP
        Object template) {

    public OperationDef {
        params = ordered(params);
        out = ordered(out);
        required = required == null ? List.of() : List.copyOf(required);
        piiPaths = piiPaths == null ? List.of() : List.copyOf(piiPaths);
    }

    public OperationDef withVersion(int newVersion) {
        return new OperationDef(op, newVersion, kind, resource, params, out, required, timeoutMs, idempotent, audit,
                piiPaths, request, success, classify, collection, action, filter, set, setOnInsert, inc, sort,
                projection, limit, expect, template);
    }

    private static <V> Map<String, V> ordered(Map<String, V> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
