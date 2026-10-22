package com.rupeek.flow.registry;

import com.rupeek.flow.registry.store.FlowConfigException;
import io.temporal.failure.ApplicationFailure;

import java.util.List;
import java.util.Map;

/** The real {@link ResolveTaskActivity}, backed by the registry. Mirrors {@link ResolveFlowActivityImpl}. */
public final class ResolveTaskActivityImpl implements ResolveTaskActivity {

    private final TaskRegistryService registry;

    public ResolveTaskActivityImpl(TaskRegistryService registry) {
        this.registry = registry;
    }

    @Override
    public Map<String, Integer> resolveVersions(List<String> refs) {
        try {
            return registry.resolve(refs);
        } catch (FlowConfigException e) {
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), "ConfigError");
        }
    }
}
