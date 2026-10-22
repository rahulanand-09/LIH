package com.rupeek.flow.registry.store;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * The {@code latest} pointer for one {@code type:ref}, plus its full append-only publish {@link #history}.
 * {@code createdAt} is set once, the first time this ref is ever published; {@code updatedAt}/{@code updatedBy}/
 * {@code updatedReason} mirror the newest {@code history} entry, kept as top-level fields so "list every ref with
 * its last publish info" is a plain collection scan instead of an array-element lookup per row.
 */
@Document(collection = "flow_pointer")
public record FlowPointerDocument(
        @Id String id,
        String type,
        String ref,
        int latest,
        Instant createdAt,
        Instant updatedAt,
        String updatedBy,
        String updatedReason,
        List<PointerHistoryEntry> history) {
}
