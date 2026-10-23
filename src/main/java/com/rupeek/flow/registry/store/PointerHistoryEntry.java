package com.rupeek.flow.registry.store;

import java.time.Instant;

/** One entry in a {@link FlowPointerDocument}'s append-only {@code history} — unchanged shape from before. */
public record PointerHistoryEntry(int version, String by, Instant at, String reason, String publishId) {
}
