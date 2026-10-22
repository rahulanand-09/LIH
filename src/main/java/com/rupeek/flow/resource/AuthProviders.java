package com.rupeek.flow.resource;

import com.rupeek.flow.activity.FlowTaskFailure;
import com.rupeek.flow.activity.TaskFailureType;
import com.rupeek.flow.resource.Resource.AuthSpec;
import com.rupeek.flow.resource.Resource.AuthType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The POC auth schemes and a lookup by type. Flow-native port of {@code com.rupeek.l1.ops.http.AuthProviders}. */
public final class AuthProviders {

    private final Map<AuthType, AuthProvider> byType = new EnumMap<>(AuthType.class);

    public AuthProviders(List<AuthProvider> providers) {
        providers.forEach(p -> byType.put(p.type(), p));
    }

    public static AuthProviders defaults() {
        return new AuthProviders(List.of(new NoAuth(), new ApiKeyHeaderAuth(), new StaticBearerAuth()));
    }

    public AuthProvider forType(AuthType type) {
        AuthProvider p = byType.get(type);
        if (p == null) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, "no auth provider for type " + type);
        }
        return p;
    }

    /** No authentication. */
    public static final class NoAuth implements AuthProvider {
        public AuthType type() {
            return AuthType.NONE;
        }

        public Map<String, String> headers(AuthSpec auth, SecretResolver secrets) {
            return Map.of();
        }
    }

    /** The secret sent as-is in a named header, e.g. Axis {@code API-key}, Core {@code Authorization}. */
    public static final class ApiKeyHeaderAuth implements AuthProvider {
        public AuthType type() {
            return AuthType.API_KEY_HEADER;
        }

        public Map<String, String> headers(AuthSpec auth, SecretResolver secrets) {
            if (auth.header() == null || auth.header().isBlank()) {
                throw new FlowTaskFailure(TaskFailureType.PERMANENT, "API_KEY_HEADER auth needs a 'header' name");
            }
            return Map.of(auth.header(), secrets.resolve(auth.secretRef()));
        }
    }

    /** {@code Authorization: Bearer <secret>}. */
    public static final class StaticBearerAuth implements AuthProvider {
        public AuthType type() {
            return AuthType.STATIC_BEARER;
        }

        public Map<String, String> headers(AuthSpec auth, SecretResolver secrets) {
            return Map.of("Authorization", "Bearer " + secrets.resolve(auth.secretRef()));
        }
    }
}
