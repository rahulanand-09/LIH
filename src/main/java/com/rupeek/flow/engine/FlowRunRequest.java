package com.rupeek.flow.engine;

import com.rupeek.flow.model.FlowDef;

import java.util.List;
import java.util.Map;

/**
 * The flow to run, plus every flow a SUBFLOW node inside it might reference, keyed by flowRef — a flat
 * test-fixture lookup, exactly what every toy/fixture-based test uses. A ref missing from here falls back to a
 * real {@code FetchFlowActivity} call (§9's {@code ResolveFlowActivity} already pins its version; this fetches
 * that version's actual content) — an Activity, not inline data, since a live registry/store connection can't be
 * carried as workflow-serialized state, and because the result then belongs in THIS workflow's own history like
 * everything else resolveVersions produces. {@code depth}/{@code flowRefChain} are the runtime cycle/depth
 * backstop: {@code depth} counts SUBFLOW nesting from the root, {@code flowRefChain} is every flowRef in that
 * lineage, checked before starting a child.
 */
public record FlowRunRequest(FlowDef flow, Map<String, Object> inputs, Map<String, FlowDef> registry,
                              int depth, List<String> flowRefChain) {

    /** Starts a new root case: depth 0, lineage of just this flow. */
    public static FlowRunRequest root(FlowDef flow, Map<String, Object> inputs, Map<String, FlowDef> registry) {
        return new FlowRunRequest(flow, inputs, registry, 0, List.of(flow.flowRef()));
    }
}
