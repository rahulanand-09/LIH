package com.rupeek.l1.registry;

import com.rupeek.l1.engine.ResourceProvider;
import com.rupeek.l1.model.ConfigJson;
import com.rupeek.l1.model.Resource;

import java.util.function.Function;

/** Environment selection and {@code baseUrl}/{@code baseUrlRef} resolution shared by every resource provider. */
public abstract class BaseResourceProvider implements ResourceProvider {

    /** Environment variables first, then JVM system properties. */
    public static final Function<String, String> SYSTEM_ENV = name -> {
        String value = System.getenv(name);
        return value != null ? value : System.getProperty(name);
    };

    private final String envName;
    private final Function<String, String> envLookup;

    protected BaseResourceProvider(String envName, Function<String, String> envLookup) {
        this.envName = envName;
        this.envLookup = envLookup;
    }

    @Override
    public String envName() {
        return envName;
    }

    @Override
    public String baseUrl(Resource resource) {
        Resource.ResourceEnv env = resource.env().get(envName);
        if (env == null) {
            throw new ConfigJson.ConfigException("resource '" + resource.resource() + "' has no '" + envName + "' environment");
        }
        if (env.baseUrl() != null && !env.baseUrl().isBlank()) {
            return env.baseUrl();
        }
        String fromEnv = env.baseUrlRef() == null ? null : envLookup.apply(env.baseUrlRef());
        if (fromEnv == null || fromEnv.isBlank()) {
            throw new ConfigJson.ConfigException("resource '" + resource.resource() + "': base URL variable '"
                    + env.baseUrlRef() + "' is not set");
        }
        return fromEnv;
    }
}
