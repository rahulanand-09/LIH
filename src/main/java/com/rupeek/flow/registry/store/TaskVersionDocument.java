package com.rupeek.flow.registry.store;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One immutable published {@code TaskDef} version — identical shape to {@link FlowVersionDocument}, own
 * collection. {@code spec} is authoritative; {@code specView} is a write-only BSON-tree copy for Mongo tooling
 * (see {@link FlowVersionDocument}'s javadoc for why).
 */
@Document(collection = "task_version")
@CompoundIndex(name = "ref_version_unique", def = "{'ref': 1, 'version': 1}", unique = true)
public record TaskVersionDocument(
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

    static TaskVersionDocument newVersion(StoredVersion v) {
        return new TaskVersionDocument(null, v.ref(), v.version(), v.specJson(), BsonSpecCodec.toBsonSafe(v.specJson()),
                v.specHash(), v.publishedBy(), v.publishedAt(), v.reason(), v.publishId());
    }

    StoredVersion toStoredVersion() {
        return new StoredVersion(DocType.TASK, ref, version, spec, specHash, publishedBy, publishedAt, reason, publishId);
    }
}
