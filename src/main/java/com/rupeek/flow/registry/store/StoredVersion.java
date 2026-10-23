package com.rupeek.flow.registry.store;

import java.time.Instant;

/**
 * One immutable published version of a flow. Flow-native counterpart of {@code com.rupeek.l1.registry.StoredVersion}
 * — same shape, trimmed to the one {@link DocType} this store knows about.
 *
 * @param specJson  the document as published (see {@link FlowConfigCodec#json})
 * @param specHash  {@link FlowConfigCodec#hash} of the document; equal hashes mean equal meaning
 * @param publishId the publish run that inserted it
 */
public record StoredVersion(
        DocType type,
        String ref,
        int version,
        String specJson,
        String specHash,
        String publishedBy,
        Instant publishedAt,
        String reason,
        String publishId) {
}
