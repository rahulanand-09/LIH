package com.rupeek.l1.registry;

import java.time.Instant;

/** The current document of one resource. Every change is also appended to the resource history. */
public record StoredResource(
        String resource,
        String specJson,
        String specHash,
        String updatedBy,
        Instant updatedAt,
        String reason,
        String publishId) {
}
