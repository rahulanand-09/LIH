package com.rupeek.l1.registry;

import com.rupeek.l1.ops.mongo.MongoTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoConfigStoreTest {

    private MongoConfigStore store;
    private String dbName;

    @BeforeEach
    void freshDatabase() {
        dbName = "cfg_" + UUID.randomUUID().toString().substring(0, 8);
        store = new MongoConfigStore(MongoTestSupport.client().getDatabase(dbName)).ensureIndexes();
    }

    private static StoredVersion v(DocType type, String ref, int version, String json) {
        return new StoredVersion(type, ref, version, json, "hash-" + version, "ci", Instant.parse("2026-10-18T10:00:00Z"),
                "why", "pub-1");
    }

    @Test
    void versionsAreImmutable() {
        store.insert(v(DocType.RECIPE, "a.b", 1, "{\"x\":1}"));
        assertThatThrownBy(() -> store.insert(v(DocType.RECIPE, "a.b", 1, "{\"x\":2}")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already exists");
        store.insert(v(DocType.OPERATION, "a.b", 1, "{}"));   // same ref, other type: separate collection
        assertThat(store.find(DocType.RECIPE, "a.b", 1).orElseThrow().specJson()).isEqualTo("{\"x\":1}");
    }

    @Test
    void specsWithMongoUnsafeKeysAreStoredVerbatim() {
        String json = "{\"template\":{\"$each\":\"=x\",\"a.b\":null}}";
        store.insert(v(DocType.OPERATION, "m.x", 1, json));
        StoredVersion back = store.find(DocType.OPERATION, "m.x", 1).orElseThrow();
        assertThat(back.specJson()).isEqualTo(json);
        assertThat(back.publishedAt()).isEqualTo(Instant.parse("2026-10-18T10:00:00Z"));
    }

    @Test
    void pointersAndListing() {
        store.insert(v(DocType.RECIPE, "a.b", 1, "{}"));
        store.insert(v(DocType.RECIPE, "a.b", 2, "{}"));
        store.insert(v(DocType.RECIPE, "c.d", 1, "{}"));
        assertThat(store.latest(DocType.RECIPE, "a.b")).isEmpty();
        store.movePointer(DocType.RECIPE, "a.b", 1, "ci", "first", "p1");
        store.movePointer(DocType.RECIPE, "a.b", 2, "ci", "second", "p2");
        store.movePointer(DocType.RECIPE, "c.d", 1, "ci", "first", "p1");
        assertThat(store.latest(DocType.RECIPE, "a.b")).hasValue(2);
        assertThat(store.pointers(DocType.RECIPE)).containsExactly(Map.entry("a.b", 2), Map.entry("c.d", 1));
        assertThat(store.pointers(DocType.OPERATION)).isEmpty();
        assertThat(store.all(DocType.RECIPE)).extracting(StoredVersion::version).containsExactly(1, 2, 1);
        var history = MongoTestSupport.client().getDatabase(dbName).getCollection("l1_pointer")
                .find().first().getList("history", org.bson.Document.class);
        assertThat(history).hasSize(2);
    }

    @Test
    void newestRedirectWins() {
        assertThat(store.redirect("a.b", 1)).isEmpty();
        store.addRedirect(new Redirect("a.b", 1, 2, "ops", Instant.parse("2026-10-18T10:00:00Z"), "bad v1"));
        store.addRedirect(new Redirect("a.b", 1, 1, "ops", Instant.parse("2026-10-18T11:00:00Z"), "undo"));
        assertThat(store.redirect("a.b", 1).orElseThrow().toVersion()).isEqualTo(1);
        assertThat(store.redirect("a.b", 2)).isEmpty();
    }

    @Test
    void resourcesKeepHistory() {
        store.putResource(new StoredResource("core", "{\"v\":1}", "h1", "ci", Instant.parse("2026-10-18T10:00:00Z"), "a", "p1"));
        store.putResource(new StoredResource("core", "{\"v\":2}", "h2", "ci", Instant.parse("2026-10-18T11:00:00Z"), "b", "p2"));
        assertThat(store.resource("core").orElseThrow().specJson()).isEqualTo("{\"v\":2}");
        assertThat(store.resources()).hasSize(1);
        assertThat(MongoTestSupport.client().getDatabase(dbName).getCollection("l1_resource_history")
                .countDocuments()).isEqualTo(2);
    }
}
