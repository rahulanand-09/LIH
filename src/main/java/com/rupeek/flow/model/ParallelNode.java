package com.rupeek.flow.model;

import java.util.List;

/** Every listed node runs concurrently, unconditionally (M4) — not a branch choice. */
public record ParallelNode(String id, List<String> branches) implements Node {
}
