package com.rupeek.flow.registry.store;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One immutable published {@code FlowDef} version, Spring Data's view of a {@code flow_version} row. {@code id}
 * is {@code null} on a new instance (Mongo assigns it on insert) — this store never reads it back, lookups are
 * always by the {@code ref+version} compound index.
 *
 * <p>{@code spec} (the JSON text {@link com.rupeek.flow.registry.store.FlowConfigCodec#json} produced) is
 * authoritative — hashed, parsed, loaded by {@code FlowDefLoader}/{@code TaskDefLoader}. {@code specView} is a
 * second, write-only copy of the same content as a real BSON subdocument (via {@link BsonSpecCodec}), purely so
 * {@code spec} can be browsed as an expandable tree in Mongo tooling instead of an opaque string — never read
 * back by this store ({@link #toStoredVersion} ignores it entirely).
 */
@Document(collection = "flow_version")
@CompoundIndex(name = "ref_version_unique", def = "{'ref': 1, 'version': 1}", unique = true)
public record FlowVersionDocument(
        @Id String id,
        String ref,
        int version,
        String spec,
        org.bson.Document specView,
        String specHash,
        String publishedBy,
        Instant publishedAt,
        String reason,
        String publishId) {

    static FlowVersionDocument newVersion(StoredVersion v) {
        return new FlowVersionDocument(null, v.ref(), v.version(), v.specJson(), BsonSpecCodec.toBsonSafe(v.specJson()),
                v.specHash(), v.publishedBy(), v.publishedAt(), v.reason(), v.publishId());
    }

    StoredVersion toStoredVersion() {
        return new StoredVersion(DocType.FLOW, ref, version, spec, specHash, publishedBy, publishedAt, reason, publishId);
    }
}
