package com.rupeek.flow.registry;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** What {@link ResolveTaskActivityImpl} calls: the latest published version of each task ref. Mirrors {@link FlowRegistryService}. */
public final class TaskRegistryService {

    private final TaskSource tasks;

    public TaskRegistryService(TaskSource tasks) {
        this.tasks = tasks;
    }

    /** @throws com.rupeek.flow.registry.store.FlowConfigException if a ref was never published */
    public Map<String, Integer> resolve(Collection<String> refs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String ref : refs) {
            out.put(ref, tasks.latestVersion(ref));
        }
        return out;
    }
}
