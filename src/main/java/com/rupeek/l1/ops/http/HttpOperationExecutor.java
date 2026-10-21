package com.rupeek.l1.ops.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rupeek.l1.expr.ScopeDecl;
import com.rupeek.l1.expr.TemplateEvaluator;
import com.rupeek.l1.expr.TemplateEvaluator.CompiledTemplate;
import com.rupeek.l1.model.ConfigJson.ConfigException;
import com.rupeek.l1.model.HttpRequestSpec;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.OperationKind;
import com.rupeek.l1.model.Resource;
import com.rupeek.l1.ops.ErrorClass;
import com.rupeek.l1.ops.ExecContext;
import com.rupeek.l1.ops.OperationException;
import com.rupeek.l1.ops.OperationExecutor;
import com.rupeek.l1.ops.PreparedOperation;
import com.rupeek.l1.ops.TransportException;
import com.rupeek.l1.secrets.SecretResolver;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * {@code HTTP} operations (REST). Builds the request from templates, adds static and auth headers, sends it
 * with the JDK client and returns {@code {resp: {status, headers, body}}}. Any HTTP status is returned as data;
 * deciding what it means is the engine's job ({@code success} / {@code classify}).
 *
 * <p>Safety: the host comes only from the resource; the path may not contain a scheme, a host or {@code ..};
 * redirects are not followed.
 */
public final class HttpOperationExecutor implements OperationExecutor {

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final int DEFAULT_READ_MS = 30_000;
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final TemplateEvaluator templates;
    private final HttpClientFactory clients;
    private final AuthProviders auth;
    private final SecretResolver secrets;

    public HttpOperationExecutor(TemplateEvaluator templates, HttpClientFactory clients, AuthProviders auth,
                                 SecretResolver secrets) {
        this.templates = templates;
        this.clients = clients;
        this.auth = auth;
        this.secrets = secrets;
    }

    @Override
    public OperationKind kind() {
        return OperationKind.HTTP;
    }

    record PreparedHttp(OperationDef def, String method, CompiledTemplate path, CompiledTemplate query,
                        CompiledTemplate headers, CompiledTemplate body, String encoding) implements PreparedOperation {
    }

    @Override
    public PreparedOperation prepare(OperationDef def) {
        String where = def.op() + "/request";
        if (def.resource() == null) {
            throw new ConfigException(def.op() + ": HTTP operation needs a 'resource'");
        }
        HttpRequestSpec r = def.request();
        if (r == null) {
            throw new ConfigException(where + ": HTTP operation needs a 'request'");
        }
        String method = r.method() == null ? null : r.method().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new ConfigException(where + ".method: must be one of " + METHODS + ", got " + r.method());
        }
        if (r.path() == null) {
            throw new ConfigException(where + ".path: is required");
        }
        if (!r.path().startsWith("=") || r.path().startsWith("==")) {
            checkPath(r.path().startsWith("==") ? r.path().substring(1) : r.path(), where + ".path");
        }
        String encoding = r.bodyEncoding().toUpperCase(Locale.ROOT);
        if (!encoding.equals("JSON") && !encoding.equals("FORM")) {
            throw new ConfigException(where + ".bodyEncoding: must be JSON or FORM");
        }
        if (def.success() == null) {
            throw new ConfigException(def.op() + ": HTTP operation needs a 'success' expression");
        }
        return new PreparedHttp(def, method,
                templates.compile(r.path(), ScopeDecl.OPERATION, where + ".path"),
                templates.compile(r.query(), ScopeDecl.OPERATION, where + ".query"),
                templates.compile(r.headers(), ScopeDecl.OPERATION, where + ".headers"),
                r.body() == null ? null : templates.compile(r.body(), ScopeDecl.OPERATION, where + ".body"),
                encoding);
    }

    @Override
    public Map<String, Object> execute(PreparedOperation prepared, Map<String, Object> params, ExecContext ctx)
            throws TransportException {
        PreparedHttp op = (PreparedHttp) prepared;
        Resource resource = ctx.resource();
        if (resource == null || ctx.baseUrl() == null) {
            throw new OperationException(ErrorClass.PERMANENT, op.def().op() + ": no HTTP resource or base URL resolved");
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("params", params);
        vars.put("env", ctx.env());
        vars.put("sys", ctx.sys());

        URI uri = buildUri(op, ctx.baseUrl(), templates.render(op.path(), vars), templates.render(op.query(), vars));
        Map<String, String> recordedHeaders = new LinkedHashMap<>(resource.headers());
        stringMap(templates.render(op.headers(), vars), "headers").forEach(recordedHeaders::put);
        Object body = op.body() == null ? null : templates.render(op.body(), vars);

        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(readTimeoutMs(op.def(), resource)));
        byte[] payload = encodeBody(op, body, recordedHeaders);
        try {
            recordedHeaders.forEach(request::header);
            auth.forType(resource.auth().type()).headers(resource.auth(), secrets).forEach(request::header);
            request.method(op.method(), payload == null
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(payload));
        } catch (IllegalArgumentException e) {
            throw new OperationException(ErrorClass.PERMANENT, op.def().op() + ": invalid request header: " + e.getMessage(), null, e);
        }
        ctx.recorder().request(op.method(), uri.toString(), recordedHeaders, body);

        HttpResponse<byte[]> response = send(resource, request.build());
        Map<String, Object> resp = toResp(op, response);
        ctx.recorder().response(response.statusCode(), resp.get("body"));
        Map<String, Object> scope = new HashMap<>();
        scope.put("resp", resp);
        return scope;
    }

    private HttpResponse<byte[]> send(Resource resource, HttpRequest request) throws TransportException {
        try {
            return clients.client(resource).send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException e) {
            throw new TransportException(false, true, "connect timeout: " + request.uri().getHost(), e);
        } catch (HttpTimeoutException e) {
            throw new TransportException(true, true, "no response within the timeout: " + request.uri().getHost(), e);
        } catch (ConnectException e) {
            throw new TransportException(false, false, "could not connect: " + request.uri().getHost(), e);
        } catch (IOException e) {
            // e.g. connection reset after the request was written: it may have reached the lender
            throw new TransportException(true, false, "I/O error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException(true, false, "interrupted while waiting for a response", e);
        }
    }

    private URI buildUri(PreparedHttp op, String baseUrl, Object renderedPath, Object renderedQuery) {
        if (!(renderedPath instanceof String path)) {
            throw new OperationException(ErrorClass.PERMANENT, op.def().op() + ": path must render to a string");
        }
        String p = path.startsWith("/") ? path : "/" + path;
        checkPathAtRuntime(op, p);
        StringBuilder url = new StringBuilder(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                .append(p);
        StringJoiner query = new StringJoiner("&");
        stringMap(renderedQuery, "query").forEach((k, v) -> query.add(enc(k) + "=" + enc(v)));
        if (query.length() > 0) {
            url.append(p.contains("?") ? "&" : "?").append(query);
        }
        try {
            return URI.create(url.toString());
        } catch (IllegalArgumentException e) {
            throw new OperationException(ErrorClass.PERMANENT, op.def().op() + ": invalid URL: " + e.getMessage(), null, e);
        }
    }

    private byte[] encodeBody(PreparedHttp op, Object body, Map<String, String> headers) {
        if (body == null) {
            return null;
        }
        if (op.encoding().equals("FORM")) {
            headers.putIfAbsent("Content-Type", "application/x-www-form-urlencoded");
            StringJoiner form = new StringJoiner("&");
            stringMap(body, "form body").forEach((k, v) -> form.add(enc(k) + "=" + enc(v)));
            return form.toString().getBytes(StandardCharsets.UTF_8);
        }
        if (headers.keySet().stream().noneMatch("content-type"::equalsIgnoreCase)) {
            headers.put("Content-Type", "application/json");
        }
        try {
            return JSON.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new OperationException(ErrorClass.PERMANENT, op.def().op() + ": body is not serialisable", null, e);
        }
    }

    private Map<String, Object> toResp(PreparedHttp op, HttpResponse<byte[]> response) {
        Map<String, Object> headers = new LinkedHashMap<>();
        response.headers().map().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.isEmpty() ? null : v.get(0)));
        String contentType = String.valueOf(headers.getOrDefault("content-type", "")).toLowerCase(Locale.ROOT);
        String text = new String(response.body(), StandardCharsets.UTF_8);
        Object body;
        if (text.isBlank()) {
            body = null;
        } else if (contentType.contains("json") || looksLikeJson(text)) {
            try {
                body = JSON.readValue(text, Object.class);
            } catch (JsonProcessingException e) {
                if (contentType.contains("json")) {
                    throw new OperationException(ErrorClass.PERMANENT, op.def().op() + ": response body is not valid JSON",
                            response.statusCode(), e);
                }
                body = text;
            }
        } else {
            body = text;
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", response.statusCode());
        resp.put("headers", headers);
        resp.put("body", body);
        return resp;
    }

    private static long readTimeoutMs(OperationDef def, Resource resource) {
        if (def.timeoutMs() != null) {
            return def.timeoutMs();
        }
        Resource.TimeoutSpec t = resource.timeoutMs();
        return t != null && t.read() != null ? t.read() : DEFAULT_READ_MS;
    }

    /** Config-time path check for literal paths. */
    static void checkPath(String path, String where) {
        String reason = pathProblem(path.startsWith("/") ? path : "/" + path);
        if (reason != null) {
            throw new ConfigException(where + ": " + reason + ": " + path);
        }
    }

    private static void checkPathAtRuntime(PreparedHttp op, String path) {
        String reason = pathProblem(path);
        if (reason != null) {
            throw new OperationException(ErrorClass.PERMANENT, op.def().op() + ": " + reason + ": " + path);
        }
    }

    private static String pathProblem(String path) {
        if (path.contains("://") || path.startsWith("//")) {
            return "path may not contain a scheme or host";
        }
        if (List.of(path.split("[/?]")).contains("..") || path.contains("\\")) {
            return "path may not contain '..' or '\\'";
        }
        if (path.contains("#") || path.chars().anyMatch(c -> c < 0x20)) {
            return "path contains an illegal character";
        }
        return null;
    }

    private static boolean looksLikeJson(String text) {
        String t = text.stripLeading();
        return t.startsWith("{") || t.startsWith("[");
    }

    private static Map<String, String> stringMap(Object rendered, String what) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rendered == null) {
            return out;
        }
        if (!(rendered instanceof Map<?, ?> map)) {
            throw new OperationException(ErrorClass.PERMANENT, what + " must render to an object");
        }
        map.forEach((k, v) -> {
            if (v instanceof Map || v instanceof List) {
                throw new OperationException(ErrorClass.PERMANENT, what + " value for '" + k + "' must be a scalar");
            }
            if (v != null) {
                out.put(String.valueOf(k), String.valueOf(v));
            }
        });
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
