package com.rupeek.l1.registry;

import com.rupeek.l1.model.ConfigJson;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.Recipe;
import com.rupeek.l1.model.Resource;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigCodecTest {

    private final ClasspathConfigLoader.LoadedConfig config = ClasspathConfigLoader.load("classpath:l1-config");

    @Test
    void everyDocumentSurvivesTheRoundTripThroughTheRegistry() {
        for (Recipe r : config.recipes()) {
            StoredVersion v = stored(DocType.RECIPE, r.recipe(), r.version(), ConfigCodec.json(r));
            assertThat(ConfigCodec.recipe(v)).isEqualTo(r);
            assertThat(ConfigCodec.hash(ConfigCodec.recipe(v))).isEqualTo(ConfigCodec.hash(r));
        }
        for (OperationDef o : config.operations()) {
            StoredVersion v = stored(DocType.OPERATION, o.op(), o.version(), ConfigCodec.json(o));
            OperationDef back = ConfigCodec.operation(v);
            assertThat(back).isEqualTo(o);
            assertThat(ConfigCodec.hash(back)).isEqualTo(ConfigCodec.hash(o));
        }
        for (Resource r : config.resources()) {
            StoredResource s = new StoredResource(r.resource(), ConfigCodec.json(r), ConfigCodec.hash(r), "t",
                    Instant.EPOCH, "t", "p");
            assertThat(ConfigCodec.resource(s)).isEqualTo(r);
        }
    }

    @Test
    void hashIgnoresFormattingKeyOrderAndVersionButNotContent() {
        Recipe r = ConfigJson.read("""
                {"recipe": "x.y", "outcomes": ["A"], "steps": [{"id": "s", "return": {"outcome": "A"}}],
                 "return": {"outcome": "A"}}""", Recipe.class, "a");
        Recipe reordered = ConfigJson.read("""
                {
                  "return": {"outcome": "A"},
                  "steps": [{"return": {"outcome": "A"}, "id": "s"}],
                  "outcomes": ["A"], "recipe": "x.y", "version": 7
                }""", Recipe.class, "b");
        Recipe changed = ConfigJson.read("""
                {"recipe": "x.y", "outcomes": ["A", "B"], "steps": [{"id": "s", "return": {"outcome": "A"}}],
                 "return": {"outcome": "A"}}""", Recipe.class, "c");
        assertThat(ConfigCodec.hash(reordered)).isEqualTo(ConfigCodec.hash(r));
        assertThat(ConfigCodec.hash(changed)).isNotEqualTo(ConfigCodec.hash(r));
    }

    @Test
    void nullsInsideMapsAreKept() {
        OperationDef op = ConfigJson.read("""
                {"op": "m.x", "kind": "MAP", "template": {"a": null, "b": [null]}}""", OperationDef.class, "m");
        assertThat(ConfigCodec.json(op)).contains("\"a\":null").contains("[null]");
    }

    private static StoredVersion stored(DocType type, String ref, int version, String json) {
        return new StoredVersion(type, ref, version, json, "h", "t", Instant.EPOCH, "t", "p");
    }
}
