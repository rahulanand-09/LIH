package com.rupeek.l1.model;

import java.util.List;
import java.util.Map;

/**
 * Where an operation runs and with which credentials. Read live (not pinned per case).
 *
 * @param env           one block per environment name ({@code qa}, {@code uat}, {@code prod}, {@code local})
 * @param datasourceRef MONGO only: named connection
 * @param collections   MONGO only: allowlist of collections operations may touch
 */
public record Resource(
        String resource,
        ResourceKind kind,
        AuthSpec auth,
        Map<String, String> headers,
        TimeoutSpec timeoutMs,
        PoolSpec pool,
        Map<String, ResourceEnv> env,
        String datasourceRef,
        String database,
        List<String> collections) {

    public Resource {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        env = env == null ? Map.of() : Map.copyOf(env);
        collections = collections == null ? List.of() : List.copyOf(collections);
        auth = auth == null ? new AuthSpec(AuthType.NONE, null, null) : auth;
    }

    /** Non-secret values for the active environment, or an empty map. */
    public Map<String, Object> valuesFor(String envName) {
        ResourceEnv e = env.get(envName);
        return e == null ? Map.of() : e.values();
    }

    public enum ResourceKind { HTTP, MONGO }

    public enum AuthType { NONE, API_KEY_HEADER, STATIC_BEARER }

    /** @param header header name for API_KEY_HEADER; @param secretRef name resolved by the SecretResolver */
    public record AuthSpec(AuthType type, String header, String secretRef) {
        public AuthSpec {
            type = type == null ? AuthType.NONE : type;
        }
    }

    public record TimeoutSpec(Integer connect, Integer read) {
    }

    public record PoolSpec(Integer maxConnections) {
    }

    /** @param baseUrlRef name of an environment variable holding the base URL, instead of a literal */
    public record ResourceEnv(String baseUrl, String baseUrlRef, Map<String, Object> values) {
        public ResourceEnv {
            // values may legitimately hold JSON nulls, so no Map.copyOf here
            values = values == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
        }
    }
}
