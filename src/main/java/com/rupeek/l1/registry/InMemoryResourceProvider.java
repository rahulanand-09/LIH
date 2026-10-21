package com.rupeek.l1.registry;

import com.rupeek.l1.model.ConfigJson;
import com.rupeek.l1.model.Resource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Resources held in memory (classpath config, publish validation, tests).
 * {@code baseUrlRef} values are looked up through the supplied function (environment variables by default).
 */
public final class InMemoryResourceProvider extends BaseResourceProvider {

    private final Map<String, Resource> resources = new ConcurrentHashMap<>();

    public InMemoryResourceProvider(Map<String, Resource> resources, String envName) {
        this(resources, envName, SYSTEM_ENV);
    }

    public InMemoryResourceProvider(Map<String, Resource> resources, String envName, Function<String, String> envLookup) {
        super(envName, envLookup);
        this.resources.putAll(resources);
    }

    /** Replaces one resource (resources are live config). */
    public void put(Resource resource) {
        resources.put(resource.resource(), resource);
    }

    @Override
    public Resource resource(String id) {
        Resource r = resources.get(id);
        if (r == null) {
            throw new ConfigJson.ConfigException("unknown resource '" + id + "'");
        }
        return r;
    }
}
