package com.rupeek.flow.model;

import java.util.Map;

/** A reference to another whole {@link FlowDef}, run recursively as a Temporal child workflow (M2). */
public record SubflowNode(String id, String flowRef, Map<String, String> in, OnError onError) implements Node {
}
