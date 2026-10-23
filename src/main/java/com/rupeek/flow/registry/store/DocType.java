package com.rupeek.flow.registry.store;

/**
 * The immutable, versioned document types this store holds: a {@link com.rupeek.flow.model.FlowDef} (graph
 * shape only) or a {@link com.rupeek.flow.model.TaskDef} (one leaf node's real HTTP/MONGO/MAP/GEN config,
 * fetched by {@code taskRef} instead of carried inline — the second doc type the store shape was kept generic
 * for).
 */
public enum DocType {
    FLOW,
    TASK
}
