package com.rupeek.l1.ops.http;

import com.rupeek.l1.model.Resource;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One JDK {@link HttpClient} per resource and connect timeout. Redirects are not followed: a redirect is
 * returned to the recipe as data, so config can never be bounced to another host.
 *
 * <p>Note: the JDK client has no per-client connection cap, so {@code resource.pool.maxConnections} is not
 * enforced yet; the JVM-wide {@code jdk.httpclient.connectionPoolSize} property applies.
 */
public final class HttpClientFactory {

    static final int DEFAULT_CONNECT_MS = 10_000;

    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();

    public HttpClient client(Resource resource) {
        int connectMs = connectTimeoutMs(resource);
        return clients.computeIfAbsent(resource.resource() + "|" + connectMs, k -> HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    static int connectTimeoutMs(Resource resource) {
        Resource.TimeoutSpec t = resource.timeoutMs();
        return t != null && t.connect() != null ? t.connect() : DEFAULT_CONNECT_MS;
    }
}
