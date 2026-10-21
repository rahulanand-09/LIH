package com.rupeek.l1.registry;

import java.time.Instant;

/**
 * One immutable published version of a recipe or operation.
 *
 * @param specJson  the document as published (see {@link ConfigCodec#json})
 * @param specHash  {@link ConfigCodec#hash} of the document; equal hashes mean equal meaning
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
