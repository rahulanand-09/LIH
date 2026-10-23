package com.rupeek.flow.resource;

import java.util.Map;

/**
 * Adds authentication to an HTTP request, chosen by {@code resource.auth.type}. Flow-native port of
 * {@code com.rupeek.l1.ops.http.AuthProvider}.
 */
public interface AuthProvider {

    Resource.AuthType type();

    /** Header name to value. The executor sends these but never records them. */
    Map<String, String> headers(Resource.AuthSpec auth, SecretResolver secrets);
}
