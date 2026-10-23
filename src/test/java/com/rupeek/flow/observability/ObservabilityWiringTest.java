package com.rupeek.flow.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** The Spring MeterRegistry receives what {@link FlowMetrics} records, and detaches when the context closes. */
class ObservabilityWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    @Test
    void appRegistryReceivesFlowMetricsWhileTheContextLives() {
        MeterRegistry[] holder = new MeterRegistry[1];
        runner.run(ctx -> {
            holder[0] = ctx.getBean(MeterRegistry.class);
            FlowMetrics.count(FlowMetrics.PARK_EVENTS, "flowRef", "wiring", "node", "n", "event", "entered");
            assertThat(holder[0].get(FlowMetrics.PARK_EVENTS).tag("flowRef", "wiring").counter().count()).isEqualTo(1.0);
        });
        // context closed: a later record must not reach the closed context's registry
        FlowMetrics.count(FlowMetrics.PARK_EVENTS, "flowRef", "wiring", "node", "n", "event", "entered");
        assertThat(holder[0].get(FlowMetrics.PARK_EVENTS).tag("flowRef", "wiring").counter().count()).isEqualTo(1.0);
    }
}
