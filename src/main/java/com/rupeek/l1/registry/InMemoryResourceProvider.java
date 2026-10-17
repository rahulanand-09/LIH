package com.rupeek.l1.registry;

import com.rupeek.l1.engine.ResourceProvider;
import com.rupeek.l1.model.ConfigJson;
import com.rupeek.l1.model.Resource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Resources held in memory (loaded from the classpath until the Mongo registry exists).
 * {@code baseUrlRef} values are looked up through the supplied function (environment variables by default).
 */
public final class InMemoryResourceProvider implements ResourceProvider {

    private final Map<String, Resource> resources = new ConcurrentHashMap<>();
    private final String envName;
    private final Function<String, String> envLookup;

    public InMemoryResourceProvider(Map<String, Resource> resources, String envName) {
        this(resources, envName, name -> {
            String value = System.getenv(name);
            return value != null ? value : System.getProperty(name);
        });
    }

    public InMemoryResourceProvider(Map<String, Resource> resources, String envName, Function<String, String> envLookup) {
        this.resources.putAll(resources);
        this.envName = envName;
        this.envLookup = envLookup;
    }

    /** Replaces one resource (resources are live config). */
    public void put(Resource resource) {
        resources.put(resource.resource(), resource);
    }

    @Override
    public String envName() {
        return envName;
    }

    @Override
    public Resource resource(String id) {
        Resource r = resources.get(id);
        if (r == null) {
            throw new ConfigJson.ConfigException("unknown resource '" + id + "'");
        }
        return r;
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
