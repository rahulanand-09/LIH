package com.rupeek.flow.model;

import java.util.List;

/** A flow is a graph: nodes reference tasks or other flows by ref, edges route on outcome labels. */
public record FlowDef(String flowRef, int version, String start, List<Node> nodes, List<Edge> edges) {
}
