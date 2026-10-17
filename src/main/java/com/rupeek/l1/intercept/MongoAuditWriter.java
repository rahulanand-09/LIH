package com.rupeek.l1.intercept;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Writes audit records to {@code LenderApiAuditLog} asynchronously on one background thread with a bounded
 * queue. When the queue is full the oldest pending record is dropped (and logged): audit must never slow down
 * or fail a lender call.
 */
public final class MongoAuditWriter implements AuditWriter, AutoCloseable {

    public static final String COLLECTION = "LenderApiAuditLog";
    private static final Logger LOG = LoggerFactory.getLogger(MongoAuditWriter.class);

    private final MongoCollection<Document> collection;
    private final ThreadPoolExecutor executor;

    public MongoAuditWriter(MongoDatabase database, int queueCapacity) {
        this.collection = database.getCollection(COLLECTION);
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "l1-audit-writer");
                    t.setDaemon(true);
                    return t;
                },
                (task, pool) -> {
                    LOG.warn("audit queue full, dropping the oldest pending audit record");
                    pool.getQueue().poll();
                    pool.execute(task);
                });
    }

    @Override
    public void write(AuditRecord record) {
        try {
            executor.execute(() -> insert(record));
        } catch (RuntimeException e) {
            LOG.warn("audit record not queued: {}", e.toString());
        }
    }

    private void insert(AuditRecord r) {
        try {
            collection.insertOne(new Document()
                    .append("lenderSlug", r.lenderSlug())
                    .append("customerId", r.customerId())
                    .append("loanRequestId", r.loanRequestId())
                    .append("loanId", r.loanId())
                    .append("requestType", r.requestType())
                    .append("apiEndpoint", r.apiEndpoint())
                    .append("requestPayload", r.requestPayload())
                    .append("responseCode", r.responseCode())
                    .append("responsePayload", r.responsePayload())
                    .append("status", r.status())
                    .append("errorMessage", r.errorMessage())
                    .append("executionTimeMs", r.executionTimeMs())
                    .append("apiInvokedAt", r.apiInvokedAt() == null ? null : Date.from(r.apiInvokedAt()))
                    .append("metadata", r.metadata())
                    .append("createdAt", Date.from(Instant.now())));
        } catch (RuntimeException e) {
            LOG.warn("audit record not written for {} {}: {}", r.requestType(), r.loanRequestId(), e.toString());
        }
    }

    /** Waits for queued records to be written; used on shutdown and in tests. */
    @Override
    public void close() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
