package com.rupeek.flow.resource;

import com.rupeek.flow.task.FlowTaskFailure;
import java.util.Map;

/**
 * Resources are read live (not pinned per case): a URL move or key rotation reaches running cases. Flow-native
 * port of {@code com.rupeek.l1.engine.ResourceProvider}.
 */
public interface ResourceProvider {

    /** Active environment name, e.g. {@code qa}, {@code prod}, {@code local}. */
    String envName();

    /** @throws FlowTaskFailure (PERMANENT) if the resource does not exist */
    Resource resource(String id);

    /** Non-secret values for the active environment. */
    default Map<String, Object> values(Resource resource) {
        return resource.valuesFor(envName());
    }

    /**
     * Base URL for the active environment: the literal {@code baseUrl}, or the value of the environment
     * variable named by {@code baseUrlRef}.
     *
     * @throws FlowTaskFailure (PERMANENT) if neither is configured
     */
    String baseUrl(Resource resource);
}
