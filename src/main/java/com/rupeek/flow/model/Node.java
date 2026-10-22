package com.rupeek.flow.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One shape at every recursion depth: a node is a leaf {@link TaskNode} (one Activity call) or a
 * {@link SubflowNode} (another whole flow, run as a child workflow), plus the fixed graph-control kinds.
 *
 * <p>{@code nodeType} is a separate discriminator from {@link TaskNode#kind()} (which names the operation kind,
 * e.g. HTTP/MONGO) — this is Jackson's own type tag, needed because flow definitions travel through Temporal's
 * JSON data converter and a sealed interface alone doesn't tell Jackson which record to deserialize into.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "nodeType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TaskNode.class, name = "TASK"),
        @JsonSubTypes.Type(value = SubflowNode.class, name = "SUBFLOW"),
        @JsonSubTypes.Type(value = WaitNode.class, name = "WAIT"),
        @JsonSubTypes.Type(value = ParallelNode.class, name = "PARALLEL"),
        @JsonSubTypes.Type(value = JoinNode.class, name = "JOIN"),
        @JsonSubTypes.Type(value = EndNode.class, name = "END"),
})
public sealed interface Node permits TaskNode, SubflowNode, WaitNode, ParallelNode, JoinNode, EndNode {
    String id();
}
