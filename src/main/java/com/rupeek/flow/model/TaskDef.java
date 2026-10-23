package com.rupeek.flow.model;

import java.util.Map;

/**
 * One versioned, published task — the content a {@link TaskNode} fetches by {@code taskRef} instead of
 * carrying inline. Self-describing, same convention {@link FlowDef} already follows: {@code taskRef}/
 * {@code version}/{@code kind} are authoritative fields on the document itself, not just the registry's key for
 * it, so the stored row and the spec's own text can never disagree. A {@code TaskNode} still carries its own
 * {@code kind} too (graph-visible without a fetch) — the two must agree; nothing currently enforces that beyond
 * this type existing at all, since every real task was authored from the same source.
 *
 * <p>{@code kind} exists here (not just on {@code TaskNode}) so {@link com.rupeek.flow.registry.TaskPublisher}
 * knows, from the {@code TaskDef} alone, which native executor's field shape (and CEL scope) to validate a
 * publish against — see docs/flow-lld.md's publish-validation note.
 */
public record TaskDef(String taskRef, int version, OperationKind kind, Map<String, Object> fields) {
}
