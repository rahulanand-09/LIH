package com.rupeek.flow.observability;

import com.rupeek.flow.config.FlowProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Wires the observability pieces: the request-id servlet filter, the bridge from Spring's {@link MeterRegistry}
 * to the static {@link FlowMetrics} facade, and the one-line startup banner of effective (non-secret) config.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration"})
public class ObservabilityAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(ObservabilityAutoConfiguration.class);

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public FilterRegistrationBean<RequestIdFilter> flowRequestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> reg = new FilterRegistrationBean<>(new RequestIdFilter());
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    /** Attaches the app's registry (Prometheus etc.) to Micrometer's global registry that {@link FlowMetrics} uses. */
    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    public DisposableBean flowMetricsBinding(MeterRegistry registry) {
        Metrics.addRegistry(registry);
        return () -> Metrics.removeRegistry(registry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "flow", name = "enabled", havingValue = "true")
    public ApplicationListener<ApplicationReadyEvent> flowStartupBanner(FlowProperties p, Environment env) {
        return event -> LOG.info("LIH started: env={} profiles={} taskQueue={} namespace={} temporal={} registryMode={} "
                        + "registryDb={} auditEnabled={} auditDb={} businessMongoDatasource={} redisCache={} seedEnabled={}",
                p.getEnv(), Arrays.toString(env.getActiveProfiles()), p.getTemporal().getTaskQueue(),
                p.getTemporal().getNamespace(), p.getTemporal().getTarget(), p.getRegistry().getMode(),
                p.getRegistry().getDatabase(), p.getAudit().isEnabled(), p.getAudit().getDatabase(),
                p.getMongoDatasource(), p.getRegistry().getCache().isEnabled(),
                env.getProperty("flow.seed.enabled", "n/a"));
    }
}
