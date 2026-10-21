package com.rupeek.l1.engine;

import com.rupeek.l1.model.Resource;

import java.util.Map;

/**
 * Resources are read live (not pinned per case): a URL move or key rotation reaches running cases.
 */
public interface ResourceProvider {

    /** Active environment name, e.g. {@code qa}, {@code prod}, {@code local}. */
    String envName();

    /** @throws com.rupeek.l1.model.ConfigJson.ConfigException if the resource does not exist */
    Resource resource(String id);

    /** Non-secret values for the active environment. */
    default Map<String, Object> values(Resource resource) {
        return resource.valuesFor(envName());
    }

    /**
     * Base URL for the active environment: the literal {@code baseUrl}, or the value of the environment
     * variable named by {@code baseUrlRef}.
     *
     * @throws com.rupeek.l1.model.ConfigJson.ConfigException if neither is configured
     */
    String baseUrl(Resource resource);
}
