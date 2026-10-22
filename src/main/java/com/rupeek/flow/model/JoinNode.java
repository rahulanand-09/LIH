package com.rupeek.flow.model;

import java.util.List;

/** {@code policy} is {@code "ALL"} only, for now (M4): fires once every node in {@code from} has resolved. */
public record JoinNode(String id, String policy, List<String> from) implements Node {
}
