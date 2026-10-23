package com.rupeek.flow.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/** One INFO line per Mongo node call: operation, collection and counts only — never filters or documents. */
public final class MongoCallLog {

    private static final Logger LOG = LoggerFactory.getLogger("com.rupeek.flow.task.mongo");

    private MongoCallLog() {
    }

    /** @param result the executor's plain result: a document (find-one / write counts), a list (find-many) or null */
    public static void done(String action, String database, String collection, Object result, long latencyMs) {
        String counts;
        if (result instanceof List<?> rows) {
            counts = "returned=" + rows.size();
        } else if (result instanceof Map<?, ?> m && (m.containsKey("matched") || m.containsKey("inserted"))) {
            counts = m.containsKey("matched") ? "matched=" + m.get("matched") + " modified=" + m.get("modified")
                    : "inserted=" + m.get("inserted");
        } else {
            counts = "found=" + (result != null);
        }
        LOG.info("mongo op={} db={} collection={} {} latencyMs={}", action, database, collection, counts, latencyMs);
    }
}
