package com.rupeek.l1.worker;

import com.mongodb.client.MongoClient;
import com.rupeek.l1.intercept.AuditWriter;
import com.rupeek.l1.intercept.MongoAuditWriter;
import com.rupeek.l1.secrets.EnvSecretResolver;
import com.rupeek.l1.secrets.SecretResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.util.Map;

/**
 * Spring wiring for the L1 worker, registered as an auto-configuration so it needs no component scan of
 * {@code com.rupeek.l1}. Active only with {@code l1.enabled=true}.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration"})
@ConditionalOnProperty(prefix = "l1", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(L1Properties.class)
public class L1AutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SecretResolver l1SecretResolver() {
        return new EnvSecretResolver();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "l1.audit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MongoAuditWriter l1AuditWriter(L1Properties props, MongoClient mongoClient) {
        return new MongoAuditWriter(mongoClient.getDatabase(props.getAudit().getDatabase()),
                props.getAudit().getQueueCapacity());
    }

    @Bean
    public L1Components l1Components(L1Properties props, MongoClient mongoClient, SecretResolver secrets,
                                     ObjectProvider<MongoAuditWriter> auditWriter,
                                     ObjectProvider<MeterRegistry> meters,
                                     ObjectProvider<ObservationRegistry> observations) {
        AuditWriter writer = auditWriter.getIfAvailable();
        return L1Components.create(new L1Components.Settings(
                props.getEnv(),
                props.getConfigLocation(),
                Map.of(props.getMongoDatasource(), mongoClient),
                secrets,
                writer,
                meters.getIfAvailable(SimpleMeterRegistry::new),
                observations.getIfAvailable(() -> ObservationRegistry.NOOP),
                name -> {
                    String value = System.getenv(name);
                    return value != null ? value : System.getProperty(name);
                },
                Clock.systemUTC()));
    }

    @Bean
    @ConditionalOnProperty(prefix = "l1.temporal", name = "enabled", havingValue = "true", matchIfMissing = true)
    public L1TemporalWorker l1TemporalWorker(L1Properties props, L1Components components) {
        return new L1TemporalWorker(props.getTemporal(), components.activity(), props.getDev().isProbeWorkflow());
    }
}
