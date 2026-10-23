package com.rupeek.flow.registry.store;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@link ConfigStore} in its own {@code lih_flow_config} database — a flow-owned registry, not a collection
 * shared with {@code com.rupeek.l1}'s {@code lih_config} (which no longer exists anyway). Specs are stored as
 * JSON strings, not BSON documents: config keys such as dotted field names are not valid Mongo field names, and
 * the stored text must be exactly what was validated.
 *
 * <p>Collections: {@code flow_version}/{@code task_version} (one versions collection per {@link DocType}, each
 * unique on {@code ref+version}), one shared {@code flow_pointer} (already keyed by {@code type.name()+":"+ref},
 * same as L1's single {@code l1_pointer} collection served all three of its doc types).
 */
public final class MongoConfigStore implements ConfigStore {

    private final MongoCollection<Document> flowVersions;
    private final MongoCollection<Document> taskVersions;
    private final MongoCollection<Document> pointers;

    public MongoConfigStore(MongoDatabase db) {
        this.flowVersions = db.getCollection("flow_version");
        this.taskVersions = db.getCollection("task_version");
        this.pointers = db.getCollection("flow_pointer");
    }

    /** Creates the indexes; safe to call repeatedly. */
    public MongoConfigStore ensureIndexes() {
        IndexOptions unique = new IndexOptions().unique(true);
        flowVersions.createIndex(Indexes.ascending("ref", "version"), unique);
        taskVersions.createIndex(Indexes.ascending("ref", "version"), unique);
        return this;
    }

    private MongoCollection<Document> versions(DocType type) {
        return switch (type) {
            case FLOW -> flowVersions;
            case TASK -> taskVersions;
        };
    }

    @Override
    public Optional<StoredVersion> find(DocType type, String ref, int version) {
        Document d = versions(type).find(Filters.and(Filters.eq("ref", ref), Filters.eq("version", version))).first();
        return Optional.ofNullable(d).map(x -> toVersion(type, x));
    }

    @Override
    public OptionalInt latest(DocType type, String ref) {
        Document d = pointers.find(Filters.eq("_id", pointerId(type, ref))).first();
        return d == null ? OptionalInt.empty() : OptionalInt.of(d.getInteger("latest"));
    }

    @Override
    public Map<String, Integer> pointers(DocType type) {
        Map<String, Integer> out = new LinkedHashMap<>();
        pointers.find(Filters.eq("type", type.name())).sort(Sorts.ascending("ref"))
                .forEach(d -> out.put(d.getString("ref"), d.getInteger("latest")));
        return out;
    }

    @Override
    public void insert(StoredVersion v) {
        Document d = new Document("ref", v.ref())
                .append("version", v.version())
                .append("spec", v.specJson())
                .append("specHash", v.specHash())
                .append("publishedBy", v.publishedBy())
                .append("publishedAt", Date.from(v.publishedAt()))
                .append("reason", v.reason())
                .append("publishId", v.publishId());
        try {
            versions(v.type()).insertOne(d);
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                throw new IllegalStateException(v.type() + " " + v.ref() + "@" + v.version() + " already exists", e);
            }
            throw e;
        }
    }

    @Override
    public void movePointer(DocType type, String ref, int version, String by, String reason, String publishId) {
        Document move = new Document("version", version).append("by", by).append("at", new Date())
                .append("reason", reason).append("publishId", publishId);
        pointers.updateOne(Filters.eq("_id", pointerId(type, ref)),
                Updates.combine(Updates.set("type", type.name()), Updates.set("ref", ref),
                        Updates.set("latest", version), Updates.push("history", move)),
                new UpdateOptions().upsert(true));
    }

    private static String pointerId(DocType type, String ref) {
        return type.name() + ":" + ref;
    }

    private static StoredVersion toVersion(DocType type, Document d) {
        return new StoredVersion(type, d.getString("ref"), d.getInteger("version"), d.getString("spec"),
                d.getString("specHash"), d.getString("publishedBy"), instant(d, "publishedAt"), d.getString("reason"),
                d.getString("publishId"));
    }

    private static Instant instant(Document d, String field) {
        Date date = d.getDate(field);
        return date == null ? null : date.toInstant();
    }
}
