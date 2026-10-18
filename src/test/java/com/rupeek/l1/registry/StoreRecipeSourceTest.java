package com.rupeek.l1.registry;

import com.rupeek.l1.engine.ConfigCompiler;
import com.rupeek.l1.expr.ExpressionEngine;
import com.rupeek.l1.expr.TemplateEvaluator;
import com.rupeek.l1.model.ConfigJson.ConfigException;
import com.rupeek.l1.publish.ConfigBundle;
import com.rupeek.l1.publish.ConfigDirs;
import com.rupeek.l1.publish.Publisher;
import com.rupeek.l1.publish.SchemaValidator;
import com.rupeek.l1.worker.L1Components;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreRecipeSourceTest {

    private final InMemoryConfigStore store = new InMemoryConfigStore();
    private StoreRecipeSource source;

    @BeforeEach
    void publishCheckedInConfig() {
        var report = new Publisher(store, false, Clock.systemUTC())
                .publish(ConfigBundle.load(ConfigDirs.SOURCE, new SchemaValidator()), "local", "test", "seed", false);
        assertThat(report.ok()).as(report.toText()).isTrue();
        source = newSource();
    }

    private StoreRecipeSource newSource() {
        ExpressionEngine expressions = L1Components.standardExpressions();
        var resources = new StoreResourceProvider(store, "local", n -> "http://x", Duration.ofSeconds(60));
        var operations = L1Components.standardOperations(new TemplateEvaluator(expressions), Map.of(), r -> "s");
        return new StoreRecipeSource(store, new ConfigCompiler(expressions, operations, resources));
    }

    @Test
    void compilesPublishedVersionsAtStartUp() {
        assertThat(source.warmUp()).isEqualTo(1);
        assertThat(source.latestVersion("axis.precheck.dedupe")).isEqualTo(1);
        assertThat(source.recipe("axis.precheck.dedupe", 1).steps()).hasSize(8);
        assertThat(new RegistryService(source).resolve(java.util.List.of("axis.precheck.dedupe")))
                .isEqualTo(Map.of("axis.precheck.dedupe", 1));
    }

    @Test
    void aVersionPublishedLaterIsPickedUpWithoutARestart() {
        source.warmUp();
        StoredVersion v1 = store.find(DocType.RECIPE, "axis.precheck.dedupe", 1).orElseThrow();
        store.insert(new StoredVersion(DocType.RECIPE, v1.ref(), 2, v1.specJson().replace("\"version\":1", "\"version\":2"),
                "h2", "t", Instant.now(), "t", "p"));
        store.movePointer(DocType.RECIPE, v1.ref(), 2, "t", "t", "p");
        assertThat(source.latestVersion("axis.precheck.dedupe")).isEqualTo(2);
        assertThat(source.recipe("axis.precheck.dedupe", 2).version()).isEqualTo(2);
    }

    @Test
    void redirectsApplyToPinnedVersionsImmediately() {
        aVersionPublishedLaterIsPickedUpWithoutARestart();
        store.addRedirect(new Redirect("axis.precheck.dedupe", 1, 2, "ops", Instant.now(), "v1 is broken"));
        assertThat(source.recipe("axis.precheck.dedupe", 1).version()).isEqualTo(2);
        store.addRedirect(new Redirect("axis.precheck.dedupe", 1, 1, "ops", Instant.now(), "undo"));
        assertThat(source.recipe("axis.precheck.dedupe", 1).version()).isEqualTo(1);
    }

    @Test
    void unknownRecipesFail() {
        assertThatThrownBy(() -> source.recipe("nope.x", 1)).isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> source.latestVersion("nope.x")).isInstanceOf(ConfigException.class);
    }

    @Test
    void startUpFailsWhenAStoredVersionNoLongerCompiles() {
        StoredVersion v1 = store.find(DocType.RECIPE, "axis.precheck.dedupe", 1).orElseThrow();
        store.insert(new StoredVersion(DocType.RECIPE, v1.ref(), 2,
                v1.specJson().replace("\"version\":1", "\"version\":2").replace("rk.nameSplit", "rk.gone")
                        .replace("input.loanRequestId + ':r'", "rk.removedFn(input.loanRequestId) + ':r'"),
                "h2", "t", Instant.now(), "t", "p"));
        assertThatThrownBy(newSource()::warmUp).hasMessageContaining("removedFn");
    }
}
