package com.rupeek.flow.resource;

import com.rupeek.flow.activity.FlowTaskFailure;
import com.rupeek.flow.activity.TaskFailureType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Resources held in memory, loaded from classpath JSON at construction. Flow-native port of
 * {@code com.rupeek.l1.registry.InMemoryResourceProvider} + {@code BaseResourceProvider} collapsed into one
 * class — flow has no Mongo-registry-backed resource provider (the plan keeps resources classpath-only), so
 * there was nothing left to share a base class with.
 */
public final class ClasspathResourceProvider implements ResourceProvider {

    /** Environment variables first, then JVM system properties. */
    public static final Function<String, String> SYSTEM_ENV = name -> {
        String value = System.getenv(name);
        return value != null ? value : System.getProperty(name);
    };

    private final Map<String, Resource> resources = new ConcurrentHashMap<>();
    private final String envName;
    private final Function<String, String> envLookup;

    public ClasspathResourceProvider(Map<String, Resource> resources, String envName) {
        this(resources, envName, SYSTEM_ENV);
    }

    public ClasspathResourceProvider(Map<String, Resource> resources, String envName, Function<String, String> envLookup) {
        this.envName = envName;
        this.envLookup = envLookup;
        this.resources.putAll(resources);
    }

    @Override
    public String envName() {
        return envName;
    }

    @Override
    public Resource resource(String id) {
        Resource r = resources.get(id);
        if (r == null) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, "unknown resource '" + id + "'");
        }
        return r;
    }

    @Override
    public String baseUrl(Resource resource) {
        Resource.ResourceEnv env = resource.env().get(envName);
        if (env == null) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT,
                    "resource '" + resource.resource() + "' has no '" + envName + "' environment");
        }
        if (env.baseUrl() != null && !env.baseUrl().isBlank()) {
            return env.baseUrl();
        }
        String fromEnv = env.baseUrlRef() == null ? null : envLookup.apply(env.baseUrlRef());
        if (fromEnv == null || fromEnv.isBlank()) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT,
                    "resource '" + resource.resource() + "': base URL variable '" + env.baseUrlRef() + "' is not set");
        }
        return fromEnv;
    }
}
