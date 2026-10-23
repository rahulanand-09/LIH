package com.rupeek.flow.task.executor.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.rupeek.flow.task.FlowTaskFailure;
import com.rupeek.flow.task.TaskFailureType;

import java.util.Map;

/**
 * Maps a resource's {@code datasourceRef} + {@code database} to a Mongo database. Flow-native port of
 * {@code com.rupeek.l1.ops.mongo.MongoDatabaseProvider}.
 */
public final class MongoDatabaseProvider {

    private final Map<String, MongoClient> clients;

    public MongoDatabaseProvider(Map<String, MongoClient> clients) {
        this.clients = Map.copyOf(clients);
    }

    public MongoDatabase database(String datasourceRef, String database) {
        MongoClient client = clients.get(datasourceRef);
        if (client == null) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, "no Mongo datasource '" + datasourceRef + "' is configured");
        }
        if (database == null || database.isBlank()) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, "Mongo resource for '" + datasourceRef + "' has no 'database'");
        }
        return client.getDatabase(database);
    }
}
