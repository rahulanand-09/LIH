package com.rupeek.l1.worker;

import com.mongodb.client.MongoClient;
import com.rupeek.l1.intercept.MongoAuditWriter;
import com.rupeek.l1.ops.mongo.MongoTestSupport;
import com.rupeek.l1.publish.ConfigBundle;
import com.rupeek.l1.publish.ConfigDirs;
import com.rupeek.l1.publish.Publisher;
import com.rupeek.l1.publish.SchemaValidator;
import com.rupeek.l1.registry.MongoConfigStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class L1AutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(L1AutoConfiguration.class))
            .withBean(MongoClient.class, MongoTestSupport::client,
                    bd -> bd.setDestroyMethodName(""));   // shared test client: the context must not close it

    @Test
    void offByDefaultSoItNeverChangesTheHostApplication() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(L1Components.class);
            assertThat(ctx).doesNotHaveBean(L1TemporalWorker.class);
        });
    }

    @Test
    void enabledWiresTheWorkerAndCompilesTheClasspathConfig() {
        runner.withPropertyValues("l1.enabled=true", "l1.temporal.enabled=false").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(L1Components.class);
            assertThat(ctx).hasSingleBean(MongoAuditWriter.class);
            assertThat(ctx).doesNotHaveBean(L1TemporalWorker.class);
            L1Components components = ctx.getBean(L1Components.class);
            assertThat(components.recipes().latestVersion("axis.precheck.dedupe")).isEqualTo(1);
            assertThat(components.recipes().recipe("axis.precheck.dedupe", 1).timeoutBudgetMs()).isPositive();
        });
    }

    @Test
    void temporalWorkerStartsWithTheContextAndFailsFastWhenTemporalIsUnreachable() {
        runner.withPropertyValues("l1.enabled=true", "l1.audit.enabled=false", "l1.temporal.target=localhost:1")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("Connection refused");
                });
    }

    @Test
    void mongoRegistryModeRunsPublishedVersions() {
        String db = "reg_" + UUID.randomUUID().toString().substring(0, 8);
        var store = new MongoConfigStore(MongoTestSupport.client().getDatabase(db)).ensureIndexes();
        var report = new Publisher(store, false, Clock.systemUTC())
                .publish(ConfigBundle.load(ConfigDirs.SOURCE, new SchemaValidator()), "local", "test", "seed", false);
        assertThat(report.ok()).as(report.toText()).isTrue();

        runner.withPropertyValues("l1.enabled=true", "l1.temporal.enabled=false", "l1.audit.enabled=false",
                "l1.registry.mode=mongo", "l1.registry.database=" + db).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            L1Components components = ctx.getBean(L1Components.class);
            assertThat(components.registry().resolve(List.of("axis.precheck.dedupe")))
                    .isEqualTo(Map.of("axis.precheck.dedupe", 1));
            assertThat(components.resources().resource("core").env()).containsKey("qa");
        });
    }

    @Test
    void anUnknownRegistryModeStopsStartUp() {
        runner.withPropertyValues("l1.enabled=true", "l1.temporal.enabled=false", "l1.registry.mode=git")
                .run(ctx -> assertThat(ctx.getStartupFailure()).rootCause()
                        .hasMessageContaining("l1.registry.mode must be 'classpath' or 'mongo'"));
    }
}
