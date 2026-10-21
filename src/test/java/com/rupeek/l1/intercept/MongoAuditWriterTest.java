package com.rupeek.l1.intercept;

import com.mongodb.client.MongoDatabase;
import com.rupeek.l1.ops.mongo.MongoTestSupport;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MongoAuditWriterTest {

    @Test
    void writesWithLendercoreFieldNames() throws Exception {
        MongoDatabase db = MongoTestSupport.client().getDatabase("l1audit");
        db.getCollection(MongoAuditWriter.COLLECTION).drop();
        Instant invoked = Instant.parse("2026-10-17T10:00:00Z");

        try (MongoAuditWriter writer = new MongoAuditWriter(db, 10)) {
            writer.write(new AuditRecord("axis", "C1", "LR-1", null, "PRECHECK_DEDUPE", "https://lender/x",
                    "{\"a\":1}", "200", "{\"b\":2}", "SUCCESS", null, 42, invoked, Map.of("stepId", "call")));
        }

        Document doc = db.getCollection(MongoAuditWriter.COLLECTION).find().first();
        assertThat(doc).isNotNull();
        assertThat(doc.getString("lenderSlug")).isEqualTo("axis");
        assertThat(doc.getString("loanRequestId")).isEqualTo("LR-1");
        assertThat(doc.getString("requestType")).isEqualTo("PRECHECK_DEDUPE");
        assertThat(doc.getString("status")).isEqualTo("SUCCESS");
        assertThat(doc.getLong("executionTimeMs")).isEqualTo(42L);
        assertThat(doc.get("apiInvokedAt")).isEqualTo(Date.from(invoked));
        assertThat(doc.get("createdAt")).isInstanceOf(Date.class);
        assertThat(doc.get("metadata", Document.class).getString("stepId")).isEqualTo("call");
    }
}
