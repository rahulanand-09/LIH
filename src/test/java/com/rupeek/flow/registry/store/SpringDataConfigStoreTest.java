package com.rupeek.flow.registry.store;

import com.rupeek.flow.activity.FlowMongoTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SpringDataConfigStore} against a real (Testcontainers) Mongo — repositories built directly via
 * {@link MongoRepositoryFactory}, no Spring context needed, same "hand-wire it" convention
 * {@link com.rupeek.flow.activity.TestNativeExecutors} already uses for the native executors.
 */
class SpringDataConfigStoreTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private SpringDataConfigStore store;

    @BeforeEach
    void setUp() {
        MongoTemplate template = new MongoTemplate(
                new SimpleMongoClientDatabaseFactory(FlowMongoTestSupport.client(), "spring_data_config_store_test"));
        template.getDb().drop();
        MongoRepositoryFactory factory = new MongoRepositoryFactory(template);
        FlowVersionRepository flowVersions = factory.getRepository(FlowVersionRepository.class);
        TaskVersionRepository taskVersions = factory.getRepository(TaskVersionRepository.class);
        FlowPointerRepository pointers = factory.getRepository(FlowPointerRepository.class);
        store = new SpringDataConfigStore(flowVersions, taskVersions, pointers, template, clock).ensureIndexes();
    }

    @Test
    void insertedVersionIsFoundByRefAndVersionAndASecondInsertOfTheSameRefVersionThrows() {
        StoredVersion v = version(DocType.FLOW, "my-flow", 1, "publisher-a", "first cut");
        store.insert(v);

        assertThat(store.find(DocType.FLOW, "my-flow", 1)).contains(v);
        assertThatThrownBy(() -> store.insert(v)).isInstanceOf(IllegalStateException.class);
        assertThat(store.find(DocType.FLOW, "other-flow", 1)).isEmpty();
    }

    @Test
    void flowAndTaskVersionsAreKeptInSeparateCollectionsEvenWithTheSameRefAndVersion() {
        store.insert(version(DocType.FLOW, "shared-ref", 1, "a", "flow side"));
        store.insert(version(DocType.TASK, "shared-ref", 1, "b", "task side"));

        assertThat(store.find(DocType.FLOW, "shared-ref", 1)).get().extracting(StoredVersion::publishedBy).isEqualTo("a");
        assertThat(store.find(DocType.TASK, "shared-ref", 1)).get().extracting(StoredVersion::publishedBy).isEqualTo("b");
    }

    @Test
    void movePointerSetsCreatedAtOnceAndRefreshesUpdatedFieldsAndHistoryOnEveryLaterCall() {
        now.set(Instant.parse("2026-01-01T00:00:00Z"));
        store.movePointer(DocType.TASK, "my-task", 1, "alice", "first publish", "pub-1");

        now.set(Instant.parse("2026-01-02T00:00:00Z"));
        store.movePointer(DocType.TASK, "my-task", 2, "bob", "second publish", "pub-2");

        FlowPointerDocument doc = pointerDirect("TASK:my-task");
        assertThat(doc.latest()).isEqualTo(2);
        assertThat(doc.createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(doc.updatedAt()).isEqualTo(Instant.parse("2026-01-02T00:00:00Z"));
        assertThat(doc.updatedBy()).isEqualTo("bob");
        assertThat(doc.updatedReason()).isEqualTo("second publish");
        assertThat(doc.history()).extracting(PointerHistoryEntry::version).containsExactly(1, 2);

        assertThat(store.latest(DocType.TASK, "my-task")).hasValue(2);
    }

    @Test
    void pointersListsEveryRefOfOneTypeSortedByRef() {
        store.movePointer(DocType.FLOW, "zeta", 1, "a", "r", "p1");
        store.movePointer(DocType.FLOW, "alpha", 1, "a", "r", "p2");
        store.movePointer(DocType.TASK, "unrelated-task", 1, "a", "r", "p3");

        assertThat(store.pointers(DocType.FLOW)).containsExactly(Map.entry("alpha", 1), Map.entry("zeta", 1));
    }

    private FlowPointerDocument pointerDirect(String id) {
        MongoTemplate template = new MongoTemplate(
                new SimpleMongoClientDatabaseFactory(FlowMongoTestSupport.client(), "spring_data_config_store_test"));
        return template.findById(id, FlowPointerDocument.class);
    }

    private static StoredVersion version(DocType type, String ref, int v, String by, String reason) {
        // Millisecond-precision on purpose: Mongo's BSON Date type has no finer resolution, so Instant.now()
        // never round-trips exactly -- same truncation the old raw-driver MongoConfigStore always had too.
        Instant publishedAt = Instant.parse("2026-01-01T00:00:00.000Z");
        return new StoredVersion(type, ref, v, "{\"k\":\"v\"}", "hash-" + ref + "@" + v, by, publishedAt, reason, "publish-" + ref + "@" + v);
    }
}
