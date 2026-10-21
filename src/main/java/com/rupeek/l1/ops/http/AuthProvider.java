package com.rupeek.l1.ops.http;

import com.rupeek.l1.model.Resource;
import com.rupeek.l1.secrets.SecretResolver;

import java.util.Map;

/**
 * Adds authentication to an HTTP request, chosen by {@code resource.auth.type}. New schemes (OAuth token
 * fetch with caching, request signing) are new providers; config only names the type.
 */
public interface AuthProvider {

    Resource.AuthType type();

    /** Header name to value. The executor sends these but never records them. */
    Map<String, String> headers(Resource.AuthSpec auth, SecretResolver secrets);
}
