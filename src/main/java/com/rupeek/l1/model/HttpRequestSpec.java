package com.rupeek.l1.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP request template. {@code path}, query and header values, and {@code body} are templates:
 * a string starting with {@code =} is CEL, anything else is a literal.
 *
 * @param bodyEncoding {@code JSON} (default) or {@code FORM}
 */
public record HttpRequestSpec(
        String method,
        String path,
        Map<String, Object> query,
        Map<String, Object> headers,
        Object body,
        String bodyEncoding) {

    public HttpRequestSpec {
        query = query == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(query));
        headers = headers == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        bodyEncoding = bodyEncoding == null ? "JSON" : bodyEncoding;
    }
}
