package com.rupeek.flow.workflow;

import com.rupeek.flow.model.FlowDef;

import java.util.List;
import java.util.Map;

/**
 * The flow to run, plus every flow a SUBFLOW node inside it might reference, keyed by flowRef — a flow-lookup
 * stand-in; a real published/versioned flow store is out of scope for this engine. {@code depth}/{@code
 * flowRefChain} are the runtime cycle/depth backstop: {@code depth} counts SUBFLOW nesting from the root,
 * {@code flowRefChain} is every flowRef in that lineage, checked before starting a child.
 */
public record FlowRunRequest(FlowDef flow, Map<String, Object> inputs, Map<String, FlowDef> registry,
                              int depth, List<String> flowRefChain) {

    /** Starts a new root case: depth 0, lineage of just this flow. */
    public static FlowRunRequest root(FlowDef flow, Map<String, Object> inputs, Map<String, FlowDef> registry) {
        return new FlowRunRequest(flow, inputs, registry, 0, List.of(flow.flowRef()));
    }
}
