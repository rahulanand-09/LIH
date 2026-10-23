package com.rupeek.flow.registry;

import com.rupeek.flow.model.TaskDef;
import com.rupeek.flow.registry.store.ConfigStore;
import com.rupeek.flow.registry.store.DocType;
import com.rupeek.flow.registry.store.FlowConfigException;
import com.rupeek.flow.registry.store.StoredVersion;

/**
 * Tasks from flow's own {@link ConfigStore} — the same store a {@code FlowDef} is published into
 * ({@link DocType#TASK} instead of {@link DocType#FLOW}, one more collection, not a parallel store). Mirrors
 * {@link StoreFlowSource}: no compile step, no cache — a {@code TaskDef} is tiny config, re-parsing it on every
 * call is cheap, and it keeps "a publish takes effect at once" trivially true.
 */
public final class StoreTaskSource implements TaskSource {

    private final ConfigStore store;

    public StoreTaskSource(ConfigStore store) {
        this.store = store;
    }

    @Override
    public TaskDef task(String ref, int version) {
        StoredVersion v = store.find(DocType.TASK, ref, version)
                .orElseThrow(() -> new FlowConfigException("unknown task " + ref + "@" + version));
        return TaskDefLoader.parse(v.specJson());
    }

    @Override
    public int latestVersion(String ref) {
        return store.latest(DocType.TASK, ref).orElseThrow(() -> new FlowConfigException("unknown task " + ref));
    }
}
