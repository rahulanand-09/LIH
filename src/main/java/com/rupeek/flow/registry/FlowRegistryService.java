package com.rupeek.flow.registry;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** What {@link ResolveFlowActivityImpl} calls: the latest published version of each flow/macro ref. */
public final class FlowRegistryService {

    private final FlowSource flows;

    public FlowRegistryService(FlowSource flows) {
        this.flows = flows;
    }

    /** @throws com.rupeek.flow.registry.store.FlowConfigException if a ref was never published */
    public Map<String, Integer> resolve(Collection<String> refs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String ref : refs) {
            out.put(ref, flows.latestVersion(ref));
        }
        return out;
    }
}
