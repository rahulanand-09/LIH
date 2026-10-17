package com.rupeek.l1.worker;

import com.mongodb.client.MongoClient;
import com.rupeek.l1.intercept.MongoAuditWriter;
import com.rupeek.l1.ops.mongo.MongoTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class L1AutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(L1AutoConfiguration.class))
            .withBean(MongoClient.class, MongoTestSupport::client);

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
}
