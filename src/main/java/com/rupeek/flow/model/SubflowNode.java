package com.rupeek.flow.model;

import java.util.Map;

/**
 * A reference to another whole {@link FlowDef}. By default ({@code inline=false}) it's run recursively as a
 * Temporal child workflow (M2) — its own workflow id, its own history, independently durable/parkable.
 *
 * <p>{@code inline=true} ("macro") instead splices that flow's nodes into THIS SAME workflow execution, in
 * process, no child workflow spawned — for a reusable step-pattern (e.g. "fetch customer, check for an existing
 * record, build a stable id") that several flows repeat verbatim but that never needs to outlive its caller or
 * be independently visible as its own workflow. Authored identically to a real subflow (same {@code FlowDef}
 * shape: nodes, edges, an {@code EndNode} per exit) — only this one boolean changes how it's executed.
 *
 * <p>{@code version}, when present, PINS this reference — {@code resolveVersions} never asks the registry what's
 * latest for it at all, and this exact version is fetched forever, no matter what gets published later. {@code
 * null} (the default) is today's only previous behaviour: float to whatever the registry currently calls
 * latest. Pinning is what lets two different callers — one still declaring {@code version: 1}, a newer one
 * declaring {@code version: 2} — run correctly side by side against the same registry at the same time, instead
 * of both drifting to whatever was published most recently (docs/flow-lld.md §9).
 */
public record SubflowNode(String id, String flowRef, Map<String, String> in, OnError onError, boolean inline,
                           Integer version) implements Node {

    /** Real child workflow, floating to latest — today's original default, unchanged. */
    public SubflowNode(String id, String flowRef, Map<String, String> in, OnError onError) {
        this(id, flowRef, in, onError, false, null);
    }

    /** Macro or real child workflow, floating to latest. */
    public SubflowNode(String id, String flowRef, Map<String, String> in, OnError onError, boolean inline) {
        this(id, flowRef, in, onError, inline, null);
    }
}
