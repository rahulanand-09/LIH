package com.rupeek.flow.engine;

import java.util.Map;

/** Per-node status ({@code "PARKED"} or {@code "DONE"}; absent means not yet reached), for ops traversal. */
public record FlowState(Map<String, String> nodeStatus) {
}
