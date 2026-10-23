package com.rupeek.flow.registry.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@link ConfigStore} over Spring Data MongoDB repositories, for the real standing application (see
 * {@code FlowAutoConfiguration.MongoRegistryConfiguration}) — the hand-wired {@code main()} dev tools under
 * {@code src/test} (no Spring context) keep using {@link MongoConfigStore} directly; both implement the same
 * {@link ConfigStore} contract, so nothing downstream cares which one is behind it.
 *
 * <p>{@link #movePointer} is the one operation a plain repository {@code save()} can't express atomically (an
 * upsert that also pushes onto an array and leaves {@code createdAt} alone on every call after the first) — that
 * one goes through {@link MongoTemplate} directly, same as the raw-driver version did via {@code $push}/
 * {@code $setOnInsert}.
 */
public final class SpringDataConfigStore implements ConfigStore {

    private static final Logger LOG = LoggerFactory.getLogger(SpringDataConfigStore.class);

    private final FlowVersionRepository flowVersions;
    private final TaskVersionRepository taskVersions;
    private final FlowPointerRepository pointers;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    public SpringDataConfigStore(FlowVersionRepository flowVersions, TaskVersionRepository taskVersions,
                                 FlowPointerRepository pointers, MongoTemplate mongoTemplate, Clock clock) {
        this.flowVersions = flowVersions;
        this.taskVersions = taskVersions;
        this.pointers = pointers;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    /** Creates the {@code ref+version} unique indexes; safe to call repeatedly. */
    public SpringDataConfigStore ensureIndexes() {
        Index refVersion = new Index().on("ref", org.springframework.data.domain.Sort.Direction.ASC)
                .on("version", org.springframework.data.domain.Sort.Direction.ASC).unique();
        mongoTemplate.indexOps(FlowVersionDocument.class).ensureIndex(refVersion);
        mongoTemplate.indexOps(TaskVersionDocument.class).ensureIndex(refVersion);
        return this;
    }

    @Override
    public Optional<StoredVersion> find(DocType type, String ref, int version) {
        return switch (type) {
            case FLOW -> flowVersions.findByRefAndVersion(ref, version).map(FlowVersionDocument::toStoredVersion);
            case TASK -> taskVersions.findByRefAndVersion(ref, version).map(TaskVersionDocument::toStoredVersion);
        };
    }

    @Override
    public OptionalInt latest(DocType type, String ref) {
        return pointers.findById(pointerId(type, ref)).map(p -> OptionalInt.of(p.latest())).orElse(OptionalInt.empty());
    }

    @Override
    public Map<String, Integer> pointers(DocType type) {
        Map<String, Integer> out = new LinkedHashMap<>();
        pointers.findByTypeOrderByRefAsc(type.name()).forEach(p -> out.put(p.ref(), p.latest()));
        return out;
    }

    @Override
    public void insert(StoredVersion v) {
        try {
            switch (v.type()) {
                case FLOW -> flowVersions.insert(FlowVersionDocument.newVersion(v));
                case TASK -> taskVersions.insert(TaskVersionDocument.newVersion(v));
            }
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException(v.type() + " " + v.ref() + "@" + v.version() + " already exists", e);
        }
    }

    @Override
    public void movePointer(DocType type, String ref, int version, String by, String reason, String publishId) {
        Instant now = clock.instant();
        PointerHistoryEntry move = new PointerHistoryEntry(version, by, now, reason, publishId);
        Query query = Query.query(Criteria.where("_id").is(pointerId(type, ref)));
        Update update = new Update()
                .set("type", type.name())
                .set("ref", ref)
                .set("latest", version)
                .set("updatedAt", now)
                .set("updatedBy", by)
                .set("updatedReason", reason)
                .setOnInsert("createdAt", now)
                .push("history", move);
        mongoTemplate.upsert(query, update, FlowPointerDocument.class);
        LOG.info("registry pointer moved type={} ref={} latest={} by={} publishId={}", type, ref, version, by, publishId);
    }

    private static String pointerId(DocType type, String ref) {
        return type.name() + ":" + ref;
    }
}
