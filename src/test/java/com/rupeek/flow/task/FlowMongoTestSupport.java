package com.rupeek.flow.task;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.testcontainers.containers.MongoDBContainer;

/**
 * One Mongo container shared by all flow tests in the JVM (started on first use, stopped by Testcontainers).
 * Flow-native counterpart of {@code com.rupeek.l1.ops.mongo.MongoTestSupport} — same container image, same
 * lazy-singleton shape — kept separate so {@code com.rupeek.flow} tests have zero dependency on {@code l1}.
 */
public final class FlowMongoTestSupport {

    private static MongoDBContainer container;
    private static MongoClient client;

    private FlowMongoTestSupport() {
    }

    public static synchronized MongoClient client() {
        if (client == null) {
            container = new MongoDBContainer("mongo:7.0");
            container.start();
            client = MongoClients.create(container.getConnectionString());
        }
        return client;
    }
}
