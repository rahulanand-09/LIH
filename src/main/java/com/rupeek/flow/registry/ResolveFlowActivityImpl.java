package com.rupeek.flow.registry;

import com.rupeek.flow.registry.store.FlowConfigException;
import io.temporal.failure.ApplicationFailure;

import java.util.List;
import java.util.Map;

/**
 * The real {@link ResolveFlowActivity}, backed by the registry (mirrors {@code RegistryActivityImpl} on the L1
 * side): the latest published version of each flow/macro ref, read live on every call. Every test/demo prior to
 * this used a placeholder — {@code refs -> refs.stream().collect(toMap(r->r, r->1))} — that always answered "1";
 * this is the first point anything actually gets published and resolved from a real store.
 */
public final class ResolveFlowActivityImpl implements ResolveFlowActivity {

    private final FlowRegistryService registry;

    public ResolveFlowActivityImpl(FlowRegistryService registry) {
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
