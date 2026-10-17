package com.rupeek.l1.ops.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.rupeek.l1.model.ConfigJson;

import java.util.Map;

/**
 * Maps a resource's {@code datasourceRef} + {@code database} to a Mongo database. Connections are created by
 * the application (one client per datasource); config only names them.
 */
public final class MongoDatabaseProvider {

    private final Map<String, MongoClient> clients;

    public MongoDatabaseProvider(Map<String, MongoClient> clients) {
        this.clients = Map.copyOf(clients);
    }

    public MongoDatabase database(String datasourceRef, String database) {
        MongoClient client = clients.get(datasourceRef);
        if (client == null) {
            throw new ConfigJson.ConfigException("no Mongo datasource '" + datasourceRef + "' is configured");
        }
        if (database == null || database.isBlank()) {
            throw new ConfigJson.ConfigException("Mongo resource for '" + datasourceRef + "' has no 'database'");
        }
        return client.getDatabase(database);
    }
}
