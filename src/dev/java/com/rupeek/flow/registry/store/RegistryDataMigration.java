package com.rupeek.flow.registry.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;

/**
 * One-time data surgery on the real, already-populated {@code lih_flow_config} registry (same DB
 * {@code FlowWorkerDaemon}/the real {@code LihWorkerApplication} both point at), backfilling
 * {@code flow_version}/{@code task_version} rows published before {@code specView} existed. Uses the real
 * {@link FlowVersionRepository}/{@link TaskVersionRepository} (via {@link MongoRepositoryFactory}, no Spring
 * context needed, same pattern {@link SpringDataConfigStoreTest} uses) rather than raw driver calls, so this
 * exercises the exact same mapping the application itself uses.
 *
 * <p>Run once; not meant to be re-run as part of any build. Safe to re-run anyway — a row that already has a
 * {@code specView} is left untouched.
 */
public final class RegistryDataMigration {

    public static void main(String[] args) throws Exception {
        try (MongoClient mongo = MongoClients.create("mongodb://localhost:27018")) {
            MongoTemplate template = new MongoTemplate(new SimpleMongoClientDatabaseFactory(mongo, "lih_flow_config"));
            MongoRepositoryFactory factory = new MongoRepositoryFactory(template);

            FlowVersionRepository flowVersions = factory.getRepository(FlowVersionRepository.class);
            int flowChanged = 0;
            for (FlowVersionDocument d : flowVersions.findAll()) {
                if (d.specView() != null) {
                    continue;
                }
                flowVersions.save(new FlowVersionDocument(d.id(), d.ref(), d.version(), d.spec(),
                        BsonSpecCodec.toBsonSafe(d.spec()), d.specHash(), d.publishedBy(), d.publishedAt(), d.reason(), d.publishId()));
                flowChanged++;
            }
            System.out.println("flow_version: backfilled specView on " + flowChanged + " row(s)");

            TaskVersionRepository taskVersions = factory.getRepository(TaskVersionRepository.class);
            int taskChanged = 0;
            for (TaskVersionDocument d : taskVersions.findAll()) {
                if (d.specView() != null) {
                    continue;
                }
                taskVersions.save(new TaskVersionDocument(d.id(), d.ref(), d.version(), d.spec(),
                        BsonSpecCodec.toBsonSafe(d.spec()), d.specHash(), d.publishedBy(), d.publishedAt(), d.reason(), d.publishId()));
                taskChanged++;
            }
            System.out.println("task_version: backfilled specView on " + taskChanged + " row(s)");
        }
    }

    private RegistryDataMigration() {
    }
}
