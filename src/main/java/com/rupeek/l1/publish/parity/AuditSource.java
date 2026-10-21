package com.rupeek.l1.publish.parity;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads legacy requests from lendercore's {@code LenderApiAuditLog} (read only). One sample per loanRequestId:
 * the latest call of the given type.
 */
public final class AuditSource {

    /** @param requestPayload the request body exactly as lendercore logged it */
    public record Sample(String loanRequestId, String customerId, Instant invokedAt, String requestPayload) {
    }

    private final MongoCollection<Document> log;

    public AuditSource(MongoDatabase lendercore) {
        this.log = lendercore.getCollection("LenderApiAuditLog");
    }

    /**
     * @param loanRequestId only this one, or null for the latest {@code limit} distinct loan requests
     * @param since         only calls at or after this time, or null
     */
    public List<Sample> samples(String lender, String requestType, String loanRequestId, Instant since, int limit) {
        List<Bson> filters = new ArrayList<>(List.of(Filters.eq("lenderSlug", lender),
                Filters.eq("requestType", requestType), Filters.ne("requestPayload", null)));
        if (loanRequestId != null) {
            filters.add(Filters.eq("loanRequestId", loanRequestId));
        }
        if (since != null) {
            filters.add(Filters.gte("apiInvokedAt", Date.from(since)));
        }
        Map<String, Sample> latest = new LinkedHashMap<>();
        for (Document d : log.find(Filters.and(filters)).sort(Sorts.descending("apiInvokedAt")).limit(limit * 5)) {
            String lr = d.getString("loanRequestId");
            if (lr == null || latest.containsKey(lr)) {
                continue;
            }
            Date at = d.getDate("apiInvokedAt");
            latest.put(lr, new Sample(lr, d.getString("customerId"), at == null ? null : at.toInstant(),
                    d.getString("requestPayload")));
            if (latest.size() == limit) {
                break;
            }
        }
        return List.copyOf(latest.values());
    }
}
