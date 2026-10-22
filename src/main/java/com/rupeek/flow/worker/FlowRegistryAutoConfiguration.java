package com.rupeek.flow.worker;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.rupeek.flow.registry.store.ConfigStore;
import com.rupeek.flow.registry.store.FlowPointerRepository;
import com.rupeek.flow.registry.store.FlowVersionRepository;
import com.rupeek.flow.registry.store.RedisConfigStore;
import com.rupeek.flow.registry.store.SpringDataConfigStore;
import com.rupeek.flow.registry.store.TaskVersionRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;

/**
 * The flow registry's own Mongo wiring ({@code flow.registry.mode=mongo}), via Spring Data MongoDB repositories
 * ({@code com.rupeek.flow.registry.store.Flow/Task/VersionRepository}, {@code FlowPointerRepository}) against
 * their own {@link MongoTemplate} — deliberately NOT the application's default one, since the registry's
 * database/URI (and the app's business database, {@code lendercore}) are allowed to be entirely different.
 *
 * <p>A separate, top-level auto-configuration — not a class nested inside {@link FlowAutoConfiguration} — on
 * purpose: {@code @EnableMongoRepositories} registers its repository bean definitions while this class's
 * configuration metadata is being parsed, which turned out to run too early when nested, before
 * {@code MongoAutoConfiguration}'s own {@code afterName}-ordered {@code MongoClient} bean existed yet
 * ("No qualifying bean of type 'MongoClient'"). A standalone {@code @AutoConfiguration} with its own explicit
 * {@code after}/{@code afterName} gets Spring Boot's real two-phase auto-configuration ordering instead of the
 * weaker ordering nested configuration classes get.
 *
 * <p>The hand-wired {@code main()} dev tools under {@code src/test} (no Spring context to proxy repositories
 * with) keep using {@code MongoConfigStore} directly; both implement {@link ConfigStore}, so nothing downstream
 * cares which one is behind it.
 */
@AutoConfiguration(after = FlowAutoConfiguration.class, afterName = "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration")
@ConditionalOnProperty(prefix = "flow.registry", name = "mode", havingValue = "mongo")
@org.springframework.boot.context.properties.EnableConfigurationProperties(FlowProperties.class)
@EnableMongoRepositories(basePackages = "com.rupeek.flow.registry.store", mongoTemplateRef = "flowRegistryMongoTemplate")
public class FlowRegistryAutoConfiguration {

    @Bean
    public FlowAutoConfiguration.RegistryConnection flowRegistryConnection(FlowProperties props, MongoClient appClient) {
        String uri = props.getRegistry().getUri();
        MongoClient own = uri == null || uri.isBlank() ? null : MongoClients.create(uri);
        MongoClient client = own != null ? own : appClient;
        return new FlowAutoConfiguration.RegistryConnection(own, client, props.getRegistry().getDatabase());
    }

    @Bean
    public MongoDatabaseFactory flowRegistryMongoDbFactory(FlowAutoConfiguration.RegistryConnection registryConnection) {
        return new SimpleMongoClientDatabaseFactory(registryConnection.effectiveClient(), registryConnection.databaseName());
    }

    @Bean
    public MongoTemplate flowRegistryMongoTemplate(MongoDatabaseFactory flowRegistryMongoDbFactory) {
        return new MongoTemplate(flowRegistryMongoDbFactory);
    }

    @Bean
    public ConfigStore flowConfigStore(FlowVersionRepository flowVersions, TaskVersionRepository taskVersions,
                                       FlowPointerRepository pointers, MongoTemplate flowRegistryMongoTemplate,
                                       FlowProperties props, ObjectProvider<StringRedisTemplate> redis) {
        ConfigStore store = new SpringDataConfigStore(flowVersions, taskVersions, pointers, flowRegistryMongoTemplate,
                Clock.systemUTC()).ensureIndexes();
        FlowProperties.Cache cache = props.getRegistry().getCache();
        StringRedisTemplate template = cache.isEnabled() ? redis.getIfAvailable() : null;
        return template != null ? new RedisConfigStore(store, template, cache.getVersionTtl(), cache.getPointerTtl()) : store;
    }
}
