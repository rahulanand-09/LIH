package com.rupeek.flow.task.executor.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rupeek.flow.task.AuditFields;
import com.rupeek.flow.task.ErrorClassifier;
import com.rupeek.flow.task.FlowTaskFailure;
import com.rupeek.flow.task.TaskFailureType;
import com.rupeek.flow.task.TaskRequest;
import com.rupeek.flow.task.TaskResult;
import com.rupeek.flow.audit.TaskAuditor;
import com.rupeek.flow.audit.TaskCall;
import com.rupeek.flow.expr.ExpressionEngine;
import com.rupeek.flow.expr.ScopeDecl;
import com.rupeek.flow.expr.TemplateEvaluator;
import com.rupeek.flow.resource.AuthProviders;
import com.rupeek.flow.resource.Resource;
import com.rupeek.flow.resource.ResourceProvider;
import com.rupeek.flow.resource.SecretResolver;

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
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * {@code HTTP} nodes (REST). Builds the request from a node's own {@code fields} templates, adds static and
 * auth headers, sends it with the JDK client, and decides the outcome from {@code success}/{@code classify}.
 * Flow-native port of {@code com.rupeek.l1.ops.http.HttpOperationExecutor}, with the recipe layer's
 * {@code out}/{@code onError} evaluation folded straight in (no separate recipe step — see the plan's decision
 * #5: a BUSINESS classification with an {@code onError} mapping returns a normal {@link TaskResult} instead of
 * throwing, exactly like a recipe step's {@code onError: {"BUSINESS": "return:X"}} did).
 *
 * <p>Safety: the host comes only from the resource; the path may not contain a scheme, a host or {@code ..};
 * redirects are not followed.
 *
 * <p>{@code fields}: {@code resource}, {@code method}, {@code path}, {@code query}, {@code headers},
 * {@code body}, {@code bodyEncoding} ({@code JSON} default or {@code FORM}), {@code success} (CEL, against
 * {@code {inputs, resp, env}}), {@code classify} (CEL, same scope, returning a {@link TaskFailureType} name),
 * {@code onError} ({@code {"BUSINESS": "REJECTED"}} style — a classified failure with an entry here returns
 * that outcome instead of throwing), {@code out} (CEL, same scope), {@code idempotent}, {@code audit},
 * {@code auditKeys}, {@code piiPaths}.
 */
public final class HttpTaskExecutor {

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final int DEFAULT_READ_MS = 30_000;
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final TemplateEvaluator templates;
    private final ExpressionEngine expressions;
    private final ResourceProvider resources;
    private final HttpClientFactory clients;
    private final AuthProviders auth;
    private final SecretResolver secrets;
    private final TaskAuditor auditor;

    public HttpTaskExecutor(TemplateEvaluator templates, ExpressionEngine expressions, ResourceProvider resources,
                            HttpClientFactory clients, AuthProviders auth, SecretResolver secrets, TaskAuditor auditor) {
        this.templates = templates;
        this.expressions = expressions;
        this.resources = resources;
        this.clients = clients;
        this.auth = auth;
        this.secrets = secrets;
        this.auditor = auditor;
    }

    /**
     * Compiles every CEL-bearing part of {@code fields} — the templates ({@code path}/{@code query}/
     * {@code headers}/{@code body}) and the response-scope expressions ({@code success}/{@code classify}/
     * {@code out.*}) — without sending a request. No valid {@code inputs}/{@code resp} data is needed: compiling
     * only type-checks the expression against its {@link ScopeDecl}'s declared variable names. Called both from
     * {@link #execute} (so the two can never drift) and from a publish-time check
     * (see {@code com.rupeek.flow.registry.TaskPublisher}), which has no response to run against yet.
     */
    public void validate(Map<String, Object> fields) {
        String nodeId = "validate";
        if (!(fields.get("resource") instanceof String)) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ".fields.resource: is required");
        }
        String method = fields.get("method") instanceof String m ? m.toUpperCase(Locale.ROOT) : null;
        if (!METHODS.contains(method)) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ".fields.method: must be one of " + METHODS);
        }
        Object pathDef = fields.get("path");
        if (pathDef == null) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ".fields.path: is required");
        }
        String successExpr = fields.get("success") instanceof String s ? s : null;
        if (successExpr == null) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ".fields.success: is required");
        }
        templates.compile(pathDef, ScopeDecl.OPERATION, nodeId + ".fields.path");
        templates.compile(fields.getOrDefault("query", Map.of()), ScopeDecl.OPERATION, nodeId + ".fields.query");
        templates.compile(fields.getOrDefault("headers", Map.of()), ScopeDecl.OPERATION, nodeId + ".fields.headers");
        if (fields.get("body") != null) {
            templates.compile(fields.get("body"), ScopeDecl.OPERATION, nodeId + ".fields.body");
        }
        expressions.compile(successExpr, ScopeDecl.HTTP_RESPONSE, nodeId + ".fields.success");
        if (fields.get("classify") instanceof String c) {
            expressions.compile(c, ScopeDecl.HTTP_RESPONSE, nodeId + ".fields.classify");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> outExprs = (Map<String, Object>) fields.getOrDefault("out", Map.of());
        outExprs.forEach((name, expr) ->
                expressions.compile(String.valueOf(expr), ScopeDecl.HTTP_RESPONSE, nodeId + ".fields.out." + name));
    }

    public TaskResult execute(TaskRequest request) {
        Map<String, Object> fields = request.fields();
        String nodeId = request.nodeId();
        validate(fields);

        String resourceRef = (String) fields.get("resource");
        String method = ((String) fields.get("method")).toUpperCase(Locale.ROOT);
        Object pathDef = fields.get("path");
        String successExpr = (String) fields.get("success");
        String encoding = fields.get("bodyEncoding") instanceof String e ? e.toUpperCase(Locale.ROOT) : "JSON";
        boolean declaredIdempotent = Boolean.TRUE.equals(fields.get("idempotent"));
        @SuppressWarnings("unchecked")
        Map<String, String> onError = fields.get("onError") instanceof Map<?, ?> m
                ? ((Map<String, Object>) m).entrySet().stream().collect(LinkedHashMap::new,
                        (acc, en) -> acc.put(en.getKey(), String.valueOf(en.getValue())), Map::putAll)
                : Map.of();

        Resource resource = resources.resource(resourceRef);
        String baseUrl = resources.baseUrl(resource);
        Map<String, Object> env = resources.values(resource);

        Map<String, Object> vars = new HashMap<>();
        vars.put("inputs", request.inputs());
        vars.put("env", env);
        vars.put("sys", sys(request));

        Object renderedPath = templates.render(templates.compile(pathDef, ScopeDecl.OPERATION, nodeId + ".fields.path"), vars);
        Object renderedQuery = templates.render(
                templates.compile(fields.getOrDefault("query", Map.of()), ScopeDecl.OPERATION, nodeId + ".fields.query"), vars);
        Object renderedHeadersObj = templates.render(
                templates.compile(fields.getOrDefault("headers", Map.of()), ScopeDecl.OPERATION, nodeId + ".fields.headers"), vars);
        Object bodyDef = fields.get("body");
        Object renderedBody = bodyDef == null ? null
                : templates.render(templates.compile(bodyDef, ScopeDecl.OPERATION, nodeId + ".fields.body"), vars);

        URI uri = buildUri(nodeId, baseUrl, renderedPath, renderedQuery);
        Map<String, String> recordedHeaders = new LinkedHashMap<>(resource.headers());
        stringMap(renderedHeadersObj, nodeId + ".fields.headers").forEach(recordedHeaders::put);
        byte[] payload = encodeBody(nodeId, encoding, renderedBody, recordedHeaders);

        HttpRequest.Builder httpRequest = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(readTimeoutMs(fields, resource)));
        try {
            recordedHeaders.forEach(httpRequest::header);
            auth.forType(resource.auth().type()).headers(resource.auth(), secrets).forEach(httpRequest::header);
            httpRequest.method(method, payload == null
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(payload));
        } catch (IllegalArgumentException e) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ": invalid request header: " + e.getMessage(), e);
        }

        String target = uri.toString();
        String requestType = AuditFields.requestType(fields, request.inputs(), env, expressions, nodeId);
        String loanRequestId = AuditFields.auditKey(fields, "loanRequestId", request.inputs(), expressions, nodeId);
        String customerId = AuditFields.auditKey(fields, "customerId", request.inputs(), expressions, nodeId);
        List<String> piiPaths = AuditFields.piiPaths(fields);
        Instant invokedAt = Instant.now();
        long start = System.currentTimeMillis();

        HttpResponse<byte[]> response;
        try {
            response = clients.client(resource).send(httpRequest.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException e) {
            auditFailure(requestType, request, fields, target, loanRequestId, customerId, piiPaths, renderedBody,
                    "TIMEOUT", e.getMessage(), invokedAt, start);
            throw failureFor(ErrorClassifier.forTransport(declaredIdempotent, false), nodeId + ": connect timeout", e);
        } catch (HttpTimeoutException e) {
            auditFailure(requestType, request, fields, target, loanRequestId, customerId, piiPaths, renderedBody,
                    "TIMEOUT", e.getMessage(), invokedAt, start);
            throw failureFor(ErrorClassifier.forTransport(declaredIdempotent, true), nodeId + ": no response within the timeout", e);
        } catch (ConnectException e) {
            auditFailure(requestType, request, fields, target, loanRequestId, customerId, piiPaths, renderedBody,
                    "TECHNICAL_FAILURE", e.getMessage(), invokedAt, start);
            throw failureFor(ErrorClassifier.forTransport(declaredIdempotent, false), nodeId + ": could not connect", e);
        } catch (IOException e) {
            auditFailure(requestType, request, fields, target, loanRequestId, customerId, piiPaths, renderedBody,
                    "TECHNICAL_FAILURE", e.getMessage(), invokedAt, start);
            throw failureFor(ErrorClassifier.forTransport(declaredIdempotent, true), nodeId + ": I/O error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FlowTaskFailure(TaskFailureType.UNKNOWN, nodeId + ": interrupted while waiting for a response", e);
        }

        Map<String, Object> resp = toResp(nodeId, response);
        vars.put("resp", resp);

        if (requestType != null) {
            TaskCall call = new TaskCall(request.caseId(), nodeId, request.taskRef(), AuditFields.lender(fields),
                    loanRequestId, customerId, requestType, target, renderedBody, response.statusCode(), resp.get("body"), piiPaths);
            auditor.recordSuccess(call, invokedAt, System.currentTimeMillis() - start);
        }

        boolean success = expressions.evalBool(
                expressions.compile(successExpr, ScopeDecl.HTTP_RESPONSE, nodeId + ".fields.success"), vars);
        if (!success) {
            String classifyExpr = fields.get("classify") instanceof String c ? c : null;
            TaskFailureType type = classifyExpr != null
                    ? ErrorClassifier.parse(expressions.eval(
                            expressions.compile(classifyExpr, ScopeDecl.HTTP_RESPONSE, nodeId + ".fields.classify"), vars))
                    : ErrorClassifier.forFailedSuccess(com.rupeek.flow.model.OperationKind.HTTP, response.statusCode(),
                            ErrorClassifier.isIdempotentHttp(declaredIdempotent, method));
            String mappedOutcome = onError.get(type.name());
            if (mappedOutcome != null) {
                return new TaskResult(mappedOutcome, Map.of());
            }
            throw new FlowTaskFailure(type, nodeId + ": success check failed (HTTP " + response.statusCode() + ")",
                    response.statusCode(), null);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> outExprs = (Map<String, Object>) fields.getOrDefault("out", Map.of());
        Map<String, Object> outputs = new LinkedHashMap<>();
        outExprs.forEach((name, expr) -> outputs.put(name,
                expressions.eval(expressions.compile(String.valueOf(expr), ScopeDecl.HTTP_RESPONSE, nodeId + ".fields.out." + name), vars)));
        return new TaskResult("OK", outputs);
    }

    private void auditFailure(String requestType, TaskRequest request, Map<String, Object> fields, String target,
                              String loanRequestId, String customerId, List<String> piiPaths, Object requestBody,
                              String status, String message, Instant invokedAt, long start) {
        if (requestType == null) {
            return;
        }
        TaskCall call = new TaskCall(request.caseId(), request.nodeId(), request.taskRef(), AuditFields.lender(fields),
                loanRequestId, customerId, requestType, target, requestBody, null, null, piiPaths);
        auditor.recordFailure(call, status, message, List.of(), invokedAt, System.currentTimeMillis() - start);
    }

    private static FlowTaskFailure failureFor(TaskFailureType type, String message, Throwable cause) {
        return new FlowTaskFailure(type, message, cause);
    }

    private URI buildUri(String nodeId, String baseUrl, Object renderedPath, Object renderedQuery) {
        if (!(renderedPath instanceof String path)) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ": path must render to a string");
        }
        String p = path.startsWith("/") ? path : "/" + path;
        checkPath(nodeId, p);
        StringBuilder url = new StringBuilder(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                .append(p);
        StringJoiner query = new StringJoiner("&");
        stringMap(renderedQuery, nodeId + ".fields.query").forEach((k, v) -> query.add(enc(k) + "=" + enc(v)));
        if (query.length() > 0) {
            url.append(p.contains("?") ? "&" : "?").append(query);
        }
        try {
            return URI.create(url.toString());
        } catch (IllegalArgumentException e) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ": invalid URL: " + e.getMessage(), e);
        }
    }

    private byte[] encodeBody(String nodeId, String encoding, Object body, Map<String, String> headers) {
        if (body == null) {
            return null;
        }
        if (encoding.equals("FORM")) {
            headers.putIfAbsent("Content-Type", "application/x-www-form-urlencoded");
            StringJoiner form = new StringJoiner("&");
            stringMap(body, nodeId + " form body").forEach((k, v) -> form.add(enc(k) + "=" + enc(v)));
            return form.toString().getBytes(StandardCharsets.UTF_8);
        }
        if (headers.keySet().stream().noneMatch("content-type"::equalsIgnoreCase)) {
            headers.put("Content-Type", "application/json");
        }
        try {
            return JSON.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ": body is not serialisable", e);
        }
    }

    private Map<String, Object> toResp(String nodeId, HttpResponse<byte[]> response) {
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
                    throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ": response body is not valid JSON",
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

    private static long readTimeoutMs(Map<String, Object> fields, Resource resource) {
        if (fields.get("timeoutMs") instanceof Number n) {
            return n.longValue();
        }
        Resource.TimeoutSpec t = resource.timeoutMs();
        return t != null && t.read() != null ? t.read() : DEFAULT_READ_MS;
    }

    /** Path safety check, re-checked at every call since the path may be CEL-rendered. */
    private static void checkPath(String nodeId, String path) {
        if (path.contains("://") || path.startsWith("//")) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ": path may not contain a scheme or host: " + path);
        }
        if (List.of(path.split("[/?]")).contains("..") || path.contains("\\")) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ": path may not contain '..' or '\\': " + path);
        }
        if (path.contains("#") || path.chars().anyMatch(c -> c < 0x20)) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ": path contains an illegal character: " + path);
        }
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
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, what + " must render to an object");
        }
        map.forEach((k, v) -> {
            if (v instanceof Map || v instanceof List) {
                throw new FlowTaskFailure(TaskFailureType.PERMANENT, what + " value for '" + k + "' must be a scalar");
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

    private static Map<String, Object> sys(TaskRequest request) {
        Map<String, Object> sys = new HashMap<>();
        sys.put("now", Instant.now());
        sys.put("caseId", request.caseId());
        sys.put("runNo", request.runNo());
        return sys;
    }
}
