package com.rupeek.l1.publish;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rupeek.l1.publish.PublishReport.Action;
import com.rupeek.l1.publish.PublishReport.Item;
import com.rupeek.l1.registry.DocType;
import com.rupeek.l1.registry.InMemoryConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PublisherTest {

    private static final String RECIPE = "recipes/axis/axis.precheck.dedupe.json";
    private static final String INVOKE = "operations/axis/axis.precheck.invoke.json";

    @TempDir
    Path dir;
    private final InMemoryConfigStore store = new InMemoryConfigStore();
    private final Publisher publisher = new Publisher(store, false,
            Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void copyConfig() {
        ConfigDirs.copy(dir);
    }

    private PublishReport publish() {
        return publisher.publish(ConfigBundle.load(dir, new SchemaValidator()), "qa", "ci", "test", false);
    }

    private static Action action(PublishReport r, String ref) {
        return r.items().stream().filter(i -> i.ref().equals(ref)).findFirst().map(Item::action).orElseThrow();
    }

    @Test
    void firstPublishStoresEverythingAndMovesPointers() {
        PublishReport r = publish();
        assertThat(r.ok()).as(r.toText()).isTrue();
        assertThat(r.written()).isTrue();
        assertThat(r.goldens()).hasSize(8);
        assertThat(store.pointers(DocType.RECIPE)).isEqualTo(Map.of("axis.precheck.dedupe", 1));
        assertThat(store.pointers(DocType.OPERATION)).hasSize(7);
        assertThat(store.resources()).hasSize(3);
        assertThat(store.publishLog()).singleElement().satisfies(l -> assertThat(l.get("ok")).isEqualTo(true));
    }

    @Test
    void republishingTheSameContentIsANoOp() {
        publish();
        PublishReport again = publish();
        assertThat(again.ok()).isTrue();
        assertThat(again.items()).allSatisfy(i -> assertThat(i.action()).isEqualTo(Action.UNCHANGED));
        assertThat(store.all(DocType.RECIPE)).hasSize(1);
    }

    @Test
    void reformattingAFileIsNotAChange() throws Exception {
        publish();
        Path f = dir.resolve(INVOKE);
        Files.writeString(f, Files.readString(f).replace("\n", "\n\n   "));
        assertThat(action(publish(), "axis.precheck.invoke")).isEqualTo(Action.UNCHANGED);
    }

    @Test
    void changingAPublishedVersionInPlaceIsRefused() {
        publish();
        ConfigDirs.edit(dir.resolve(INVOKE), o -> o.put("timeoutMs", 5000));
        PublishReport r = publish();
        assertThat(r.ok()).isFalse();
        assertThat(r.written()).isFalse();
        assertThat(r.errors()).anyMatch(e -> e.contains("axis.precheck.invoke@1 is already published with different content")
                && e.contains("publish the change as @2"));
        assertThat(store.publishLog()).last().satisfies(l -> assertThat(l.get("ok")).isEqualTo(false));
    }

    @Test
    void aNewOperationVersionIsPublishedWithTheRecipeThatPinsIt() {
        publish();
        // operation v2 next to v1; recipe v2 pins it; the v1 files may stay or go (v1 is in the registry)
        ConfigDirs.edit(dir.resolve(INVOKE), o -> o.put("version", 2).put("timeoutMs", 5000));
        ConfigDirs.edit(dir.resolve(RECIPE), o -> {
            o.put("version", 2);
            for (var step : o.withArray("steps")) {
                if ("call".equals(step.get("id").asText())) {
                    ((ObjectNode) step).put("op", "axis.precheck.invoke@2");
                }
            }
        });
        renameGoldensTo(2);
        PublishReport r = publish();
        assertThat(r.ok()).as(r.toText()).isTrue();
        assertThat(action(r, "axis.precheck.invoke")).isEqualTo(Action.NEW);
        assertThat(store.latest(DocType.RECIPE, "axis.precheck.dedupe")).hasValue(2);
        assertThat(store.find(DocType.RECIPE, "axis.precheck.dedupe", 1)).isPresent();
    }

    @Test
    void versionsOnlyMoveForwardButMaySkip() {
        publish();
        ConfigDirs.edit(dir.resolve(INVOKE), o -> o.put("version", 3).put("timeoutMs", 5000));
        assertThat(publish().ok()).isTrue();   // e.g. prod skipping a version that only QA saw
        ConfigDirs.edit(dir.resolve(INVOKE), o -> o.put("version", 2).put("timeoutMs", 6000));
        assertThat(publish().errors()).anyMatch(e -> e.contains("axis.precheck.invoke@2 cannot be published")
                && e.contains("latest published is @3"));
    }

    @Test
    void aRecipeMayPinAnOperationThatIsOnlyInTheRegistry() throws Exception {
        publish();
        Files.delete(dir.resolve("operations/common/gen.stableId.json"));
        assertThat(publish().ok()).isTrue();

        PublishReport offline = new Publisher(new InMemoryConfigStore(), true, Clock.systemUTC())
                .validate(ConfigBundle.load(dir, new SchemaValidator()), "qa");
        assertThat(offline.errors()).containsExactly("recipes/axis/axis.precheck.dedupe.json: "
                + "axis.precheck.dedupe@1/steps[3:ids].op: unknown operation gen.stableId@1");
    }

    @Test
    void aFailingGoldenTestBlocksThePublish() {
        ConfigDirs.edit(dir.resolve("tests/axis/axis.precheck.dedupe/01-fresh-accepted.golden.json"),
                o -> ((ObjectNode) o.get("expect")).put("outcome", "REJECTED"));
        PublishReport r = publish();
        assertThat(r.ok()).isFalse();
        assertThat(r.written()).isFalse();
        assertThat(r.toText()).contains("FAIL fresh customer, dedupe accepted")
                .contains("expected outcome REJECTED but got INITIATED");
        assertThat(store.all(DocType.RECIPE)).isEmpty();
    }

    @Test
    void everyRecipeNeedsAGoldenTest() throws Exception {
        try (var files = Files.list(dir.resolve("tests/axis/axis.precheck.dedupe"))) {
            for (Path p : files.toList()) {
                Files.delete(p);
            }
        }
        assertThat(publish().errors()).contains("axis.precheck.dedupe@1: no golden test (add one under tests/)");
    }

    @Test
    void anHttpResourceNeedsTheTargetEnvironment() {
        PublishReport r = publisher.publish(ConfigBundle.load(dir, new SchemaValidator()), "uat", "ci", "t", true);
        assertThat(r.errors()).anyMatch(e -> e.contains("resources/core.json: no 'uat' environment"));
    }

    @Test
    void dryRunWritesNothing() {
        PublishReport r = publisher.publish(ConfigBundle.load(dir, new SchemaValidator()), "qa", "ci", "t", true);
        assertThat(r.ok()).isTrue();
        assertThat(r.written()).isFalse();
        assertThat(store.all(DocType.OPERATION)).isEmpty();
        assertThat(store.publishLog()).isEmpty();
    }

    @Test
    void aChangedResourceIsUpdatedInPlace() {
        publish();
        ConfigDirs.edit(dir.resolve("resources/core.json"), o -> ((ObjectNode) o.get("timeoutMs")).put("read", 1000));
        PublishReport r = publish();
        assertThat(action(r, "core")).isEqualTo(Action.UPDATED);
        assertThat(store.resource("core").orElseThrow().specJson()).contains("\"read\":1000");
    }

    private void renameGoldensTo(int version) {
        try (var files = Files.list(dir.resolve("tests/axis/axis.precheck.dedupe"))) {
            for (Path p : files.toList()) {
                ConfigDirs.edit(p, o -> {
                    o.put("recipe", "axis.precheck.dedupe@" + version);
                    ObjectNode mocks = (ObjectNode) o.get("mocks");
                    if (mocks.has("axis.precheck.invoke@1")) {
                        mocks.set("axis.precheck.invoke@2", mocks.remove("axis.precheck.invoke@1"));
                    }
                    ObjectNode expect = (ObjectNode) o.get("expect");
                    if (expect.has("calls") && expect.get("calls").has("axis.precheck.invoke@1")) {
                        ((ObjectNode) expect.get("calls")).set("axis.precheck.invoke@2",
                                ((ObjectNode) expect.get("calls")).remove("axis.precheck.invoke@1"));
                    }
                    if (expect.has("notCalled")) {
                        var list = expect.withArray("notCalled");
                        for (int i = 0; i < list.size(); i++) {
                            if (list.get(i).asText().equals("axis.precheck.invoke@1")) {
                                list.set(i, list.textNode("axis.precheck.invoke@2"));
                            }
                        }
                    }
                });
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
