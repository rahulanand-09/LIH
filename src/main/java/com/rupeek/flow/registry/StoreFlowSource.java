package com.rupeek.flow.registry;

import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.registry.store.ConfigStore;
import com.rupeek.flow.registry.store.DocType;
import com.rupeek.flow.registry.store.FlowConfigException;
import com.rupeek.flow.registry.store.StoredVersion;

/**
 * Flows from flow's own {@link ConfigStore}. No compile step (a flow needs no CEL compilation, just a JSON
 * parse) and no cache: a {@code FlowDef} is tiny config, re-parsing it on every call is cheap, and it keeps
 * "a publish takes effect at once" trivially true with no invalidation logic to get wrong.
 */
public final class StoreFlowSource implements FlowSource {

    private final ConfigStore store;

    public StoreFlowSource(ConfigStore store) {
        this.store = store;
    }

    @Override
    public FlowDef flow(String ref, int version) {
        StoredVersion v = store.find(DocType.FLOW, ref, version)
                .orElseThrow(() -> new FlowConfigException("unknown flow " + ref + "@" + version));
        return FlowDefLoader.parse(v.specJson());
    }

    @Override
    public int latestVersion(String ref) {
        return store.latest(DocType.FLOW, ref).orElseThrow(() -> new FlowConfigException("unknown flow " + ref));
    }
}
