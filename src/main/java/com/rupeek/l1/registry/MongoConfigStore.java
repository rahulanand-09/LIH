package com.rupeek.l1.registry;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@link ConfigStore} in the {@code lih_config} database. Specs are stored as JSON strings, not BSON documents:
 * config keys such as {@code $each} or dotted names are not valid Mongo field names, and the stored text must be
 * exactly what was validated.
 *
 * <p>Collections: {@code l1_recipe}, {@code l1_operation} (unique {@code ref+version}), {@code l1_pointer},
 * {@code l1_resource}, {@code l1_resource_history}, {@code l1_redirect}, {@code l1_publish_log}.
 */
public final class MongoConfigStore implements ConfigStore {

    private final MongoCollection<Document> recipes;
    private final MongoCollection<Document> operations;
    private final MongoCollection<Document> pointers;
    private final MongoCollection<Document> resources;
    private final MongoCollection<Document> resourceHistory;
    private final MongoCollection<Document> redirects;
    private final MongoCollection<Document> publishLog;

    public MongoConfigStore(MongoDatabase db) {
        this.recipes = db.getCollection("l1_recipe");
        this.operations = db.getCollection("l1_operation");
        this.pointers = db.getCollection("l1_pointer");
        this.resources = db.getCollection("l1_resource");
        this.resourceHistory = db.getCollection("l1_resource_history");
        this.redirects = db.getCollection("l1_redirect");
        this.publishLog = db.getCollection("l1_publish_log");
    }

    /** Creates the indexes; safe to call repeatedly. */
    public MongoConfigStore ensureIndexes() {
        IndexOptions unique = new IndexOptions().unique(true);
        recipes.createIndex(Indexes.ascending("ref", "version"), unique);
        operations.createIndex(Indexes.ascending("ref", "version"), unique);
        redirects.createIndex(Indexes.ascending("ref", "fromVersion", "at"));
        resourceHistory.createIndex(Indexes.ascending("resource", "changedAt"));
        return this;
    }

    private MongoCollection<Document> versions(DocType type) {
        return type == DocType.RECIPE ? recipes : operations;
    }

    @Override
    public Optional<StoredVersion> find(DocType type, String ref, int version) {
        Document d = versions(type).find(Filters.and(Filters.eq("ref", ref), Filters.eq("version", version))).first();
        return Optional.ofNullable(d).map(x -> toVersion(type, x));
    }

    @Override
    public List<StoredVersion> all(DocType type) {
        List<StoredVersion> out = new ArrayList<>();
        versions(type).find().sort(Sorts.ascending("ref", "version")).forEach(d -> out.add(toVersion(type, d)));
        return out;
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
    public Optional<Redirect> redirect(String ref, int fromVersion) {
        Document d = redirects.find(Filters.and(Filters.eq("ref", ref), Filters.eq("fromVersion", fromVersion)))
                .sort(Sorts.descending("at", "_id")).first();
        return Optional.ofNullable(d).map(x -> new Redirect(x.getString("ref"), x.getInteger("fromVersion"),
                x.getInteger("toVersion"), x.getString("by"), instant(x, "at"), x.getString("reason")));
    }

    @Override
    public Optional<StoredResource> resource(String id) {
        return Optional.ofNullable(resources.find(Filters.eq("_id", id)).first()).map(MongoConfigStore::toResource);
    }

    @Override
    public List<StoredResource> resources() {
        List<StoredResource> out = new ArrayList<>();
        resources.find().sort(Sorts.ascending("_id")).forEach(d -> out.add(toResource(d)));
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

    @Override
    public void putResource(StoredResource r) {
        Document d = new Document("_id", r.resource())
                .append("spec", r.specJson())
                .append("specHash", r.specHash())
                .append("updatedBy", r.updatedBy())
                .append("updatedAt", Date.from(r.updatedAt()))
                .append("reason", r.reason())
                .append("publishId", r.publishId());
        resources.replaceOne(Filters.eq("_id", r.resource()), d, new ReplaceOptions().upsert(true));
        Document h = new Document(d);
        h.remove("_id");
        h.append("resource", r.resource()).append("changedAt", Date.from(r.updatedAt()));
        resourceHistory.insertOne(h);
    }

    @Override
    public void addRedirect(Redirect r) {
        redirects.insertOne(new Document("ref", r.ref()).append("fromVersion", r.fromVersion())
                .append("toVersion", r.toVersion()).append("by", r.by()).append("at", Date.from(r.at()))
                .append("reason", r.reason()));
    }

    @Override
    public void logPublish(Map<String, Object> entry) {
        publishLog.insertOne(new Document(entry));
    }

    private static String pointerId(DocType type, String ref) {
        return type.name() + ":" + ref;
    }

    private static StoredVersion toVersion(DocType type, Document d) {
        return new StoredVersion(type, d.getString("ref"), d.getInteger("version"), d.getString("spec"),
                d.getString("specHash"), d.getString("publishedBy"), instant(d, "publishedAt"), d.getString("reason"),
                d.getString("publishId"));
    }

    private static StoredResource toResource(Document d) {
        return new StoredResource(d.getString("_id"), d.getString("spec"), d.getString("specHash"),
                d.getString("updatedBy"), instant(d, "updatedAt"), d.getString("reason"), d.getString("publishId"));
    }

    private static Instant instant(Document d, String field) {
        Date date = d.getDate(field);
        return date == null ? null : date.toInstant();
    }
}
