package com.rupeek.flow.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rupeek.flow.audit.PiiMasker;
import com.rupeek.flow.expr.fn.RkFunctions;
import com.rupeek.flow.task.TaskFailureType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Outbound HTTP call logging for {@code HttpTaskExecutor}. Rules, all enforced here so the executor stays a
 * one-liner per call site:
 * <ul>
 *   <li>Request/response headers are NEVER logged (they carry the auth header / API key).</li>
 *   <li>The URL is logged without user-info; query values of secret-looking params are replaced by {@code ***},
 *       values of params that are PII keys ({@link PiiMasker#GLOBAL_KEYS} or named by the node's {@code piiPaths})
 *       are masked like audit rows, and any known-sensitive input value appearing anywhere in the URL (e.g. a
 *       PAN in the path) is masked too.</li>
 *   <li>Bodies are logged at DEBUG only, run through the same {@link PiiMasker} and the node's own
 *       {@code piiPaths} that {@code TaskAuditor} uses. A non-JSON (plain text) body is logged as its length only.</li>
 * </ul>
 */
public final class HttpCallLog {

    private static final Logger LOG = LoggerFactory.getLogger("com.rupeek.flow.task.http");
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final PiiMasker MASKER = new PiiMasker();
    private static final int MAX_BODY = 2_000;
    private static final Set<String> SECRET_PARAMS = Set.of("token", "access_token", "refresh_token", "id_token", "api_key",
            "apikey", "api-key", "key", "secret", "client_secret", "password", "pwd", "auth", "authorization",
            "signature", "sig");

    private final String resource;
    private final String method;
    private final String url;
    private final List<String> piiPaths;

    private HttpCallLog(String resource, String method, String url, List<String> piiPaths) {
        this.resource = resource;
        this.method = method;
        this.url = url;
        this.piiPaths = piiPaths;
    }

    /**
     * One per outbound call; renders the loggable URL once. {@code inputs} and {@code body} are only inspected to
     * find values that must not appear in the URL.
     */
    public static HttpCallLog begin(String resource, String method, URI uri, List<String> piiPaths, Object body,
                                    Object inputs) {
        return new HttpCallLog(resource, method, safeUrl(uri, piiPaths, inputs, body), piiPaths);
    }

    /** Logged before the call is sent. Bodies at DEBUG only. */
    public void request(Object body) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("http request resource={} method={} url={} body={}", resource, method, url, maskedBody(body, piiPaths));
        }
    }

    /** A response arrived (any status): INFO line, metrics, DEBUG body. */
    public void response(int status, long latencyMs, Object respBody) {
        LOG.info("http call resource={} method={} url={} status={} latencyMs={}", resource, method, url, status, latencyMs);
        if (LOG.isDebugEnabled()) {
            LOG.debug("http response resource={} status={} body={}", resource, status, maskedBody(respBody, piiPaths));
        }
        FlowMetrics.count(FlowMetrics.HTTP_CALLS, "resource", resource, "status", FlowMetrics.statusClass(status));
        FlowMetrics.time(FlowMetrics.HTTP_LATENCY, Duration.ofMillis(latencyMs), "resource", resource);
    }

    /** What the node's CEL decided about a response. {@code type} is null when {@code success} was true. */
    public void verdict(int status, boolean success, TaskFailureType type, String mappedOutcome) {
        if (success) {
            LOG.debug("http verdict resource={} status={} success=true", resource, status);
        } else {
            LOG.warn("http verdict resource={} status={} success=false classify={} retryable={} mappedOutcome={}", resource,
                    status, type, type.retryable, mappedOutcome);
        }
    }

    /** No response at all (timeout / connect / I/O error). */
    public void transportFailure(long latencyMs, Exception e, TaskFailureType type) {
        LOG.warn("http call failed resource={} method={} url={} latencyMs={} error={} classify={} retryable={}", resource,
                method, url, latencyMs, e.getClass().getSimpleName(), type, type.retryable);
        FlowMetrics.count(FlowMetrics.HTTP_CALLS, "resource", resource, "status", "error");
        FlowMetrics.time(FlowMetrics.HTTP_LATENCY, Duration.ofMillis(latencyMs), "resource", resource);
    }

    /** Public for tests: the URL as it is allowed to appear in a log line. */
    public static String safeUrl(URI uri, List<String> piiPaths, Object inputs, Object body) {
        StringBuilder sb = new StringBuilder();
        if (uri.getScheme() != null) {
            sb.append(uri.getScheme()).append("://");
        }
        if (uri.getHost() != null) {
            sb.append(uri.getHost());
        }
        if (uri.getPort() != -1) {
            sb.append(':').append(uri.getPort());
        }
        if (uri.getRawPath() != null) {
            sb.append(uri.getRawPath());
        }
        Set<String> piiKeys = piiKeys(piiPaths);
        String raw = uri.getRawQuery();
        if (raw != null && !raw.isEmpty()) {
            StringJoiner q = new StringJoiner("&");
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : pair.substring(eq + 1);
                String k = URLDecoder.decode(key, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if (SECRET_PARAMS.contains(k)) {
                    value = "***";
                } else if (!value.isEmpty() && (PiiMasker.GLOBAL_KEYS.contains(k) || piiKeys.contains(k))) {
                    value = RkFunctions.mask(URLDecoder.decode(value, StandardCharsets.UTF_8), 4);
                }
                q.add(eq < 0 ? key : key + "=" + value);
            }
            sb.append('?').append(q);
        }
        // Defence in depth: a PII value that reached the URL under an innocent-looking param/path name.
        Set<String> sensitive = new HashSet<>();
        PiiMasker.collectPiiValues(inputs, false, sensitive);
        valuesUnderKeys(body, piiKeys, sensitive);
        return MASKER.maskText(sb.toString(), sensitive);
    }

    /** Scalar values (as strings) found under any map key named in {@code keys}, at any depth. */
    private static void valuesUnderKeys(Object node, Set<String> keys, Set<String> into) {
        if (node instanceof java.util.Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (keys.contains(String.valueOf(k).toLowerCase(Locale.ROOT))) {
                    leaves(v, into);
                } else {
                    valuesUnderKeys(v, keys, into);
                }
            });
        } else if (node instanceof List<?> l) {
            l.forEach(v -> valuesUnderKeys(v, keys, into));
        }
    }

    private static void leaves(Object v, Set<String> into) {
        if (v instanceof java.util.Map<?, ?> m) {
            m.values().forEach(x -> leaves(x, into));
        } else if (v instanceof List<?> l) {
            l.forEach(x -> leaves(x, into));
        } else if (v != null) {
            into.add(String.valueOf(v));
        }
    }

    private static Set<String> piiKeys(List<String> piiPaths) {
        Set<String> keys = new HashSet<>();
        for (String p : piiPaths) {
            String last = p.substring(p.lastIndexOf('.') + 1).replaceAll("\\[.*]$", "");
            keys.add(last.toLowerCase(Locale.ROOT));
        }
        return keys;
    }

    private static String maskedBody(Object body, List<String> piiPaths) {
        if (body == null) {
            return null;
        }
        if (!(body instanceof java.util.Map || body instanceof List)) {
            return "<non-structured body, " + String.valueOf(body).length() + " chars not logged>";
        }
        try {
            String json = JSON.writeValueAsString(MASKER.maskPayload(body, piiPaths));
            return json.length() <= MAX_BODY ? json : json.substring(0, MAX_BODY) + "... [TRUNCATED]";
        } catch (Exception e) {
            return "<body not serialisable>";
        }
    }
}
