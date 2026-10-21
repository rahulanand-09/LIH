package com.rupeek.flow.registry;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Mirrors {@code com.rupeek.l1.registry.RegistryService} — what {@link ResolveFlowActivityImpl} calls. */
public final class FlowRegistryService {

    private final FlowSource flows;

    public FlowRegistryService(FlowSource flows) {
        this.flows = flows;
    }

    /** @throws com.rupeek.l1.model.ConfigJson.ConfigException if a ref was never published */
    public Map<String, Integer> resolve(Collection<String> refs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String ref : refs) {
            out.put(ref, flows.latestVersion(ref));
        }
        return out;
    }
}
