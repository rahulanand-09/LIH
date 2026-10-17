package com.rupeek.l1.ops.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.testcontainers.containers.MongoDBContainer;

/** One Mongo container shared by all Mongo tests in the JVM (started on first use, stopped by Testcontainers). */
public final class MongoTestSupport {

    private static MongoDBContainer container;
    private static MongoClient client;

    private MongoTestSupport() {
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
