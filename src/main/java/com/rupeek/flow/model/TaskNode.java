package com.rupeek.flow.model;

import java.util.Map;

/**
 * A leaf: exactly one primitive operation, one Activity call. {@code taskRef} names a shared, versioned task
 * definition (for anything reused across flows); {@code fields} inlines the operation's own config directly when
 * it's one-off. {@code in} resolves this node's inputs from already-completed ancestor nodes ({@code $.nodeId.*})
 * or the flow's own input ({@code $.in.*}) — never a forward or sideways read.
 */
public record TaskNode(String id, OperationKind kind, String taskRef, Map<String, Object> fields,
                        Map<String, String> in, OnError onError) implements Node {
}
