package com.rupeek.lih.l2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RestAdapter implements ProtocolAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public Map<String, Object> execute(OperationDef op, Map<String, Object> request) throws Exception {
        String url = resolve(op.url);
        if (op.query != null && !op.query.isEmpty()) {
            url += (url.contains("?") ? "&" : "?") + op.query.entrySet().stream()
                    .map(e -> enc(e.getKey()) + "=" + enc(resolve(e.getValue()))).collect(Collectors.joining("&"));
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (op.headers != null) op.headers.forEach((k, v) -> b.header(k, resolve(v)));
        boolean hasBody = request != null && !request.isEmpty();
        b.method(op.method, hasBody ? HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request))
                                    : HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> out = new HashMap<>();
        if (resp.body() != null && !resp.body().isBlank()) {
            out.putAll(JSON.readValue(resp.body(), new TypeReference<Map<String, Object>>() {}));
        }
        out.put("_status", resp.statusCode()); // 4xx/5xx are data, the flow decides what they mean
        return out;
    }

    /** ${NAME} -> system property, then env var. Credentials never live in config, only their names. */
    static String resolve(String s) {
        if (s == null) return null;
        Matcher m = PLACEHOLDER.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = System.getProperty(m.group(1), System.getenv(m.group(1)));
            if (v == null) throw new IllegalStateException("Unresolved placeholder " + m.group(0));
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        return m.appendTail(sb).toString();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
