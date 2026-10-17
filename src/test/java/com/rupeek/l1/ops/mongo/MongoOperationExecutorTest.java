package com.rupeek.l1.ops.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.rupeek.l1.model.ConfigJson.ConfigException;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.Resource;
import com.rupeek.l1.ops.CallRecorder;
import com.rupeek.l1.ops.ErrorClass;
import com.rupeek.l1.ops.ExecContext;
import com.rupeek.l1.ops.OperationException;
import com.rupeek.l1.ops.TransportException;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.rupeek.l1.ops.OpsTestSupport.op;
import static com.rupeek.l1.ops.OpsTestSupport.resource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoOperationExecutorTest {

    private static final String DB = "l1test";
    private static final Resource RESOURCE = resource("""
            {"resource": "lendercore-mongo", "kind": "MONGO", "datasourceRef": "lendercore", "database": "l1test",
             "collections": ["LenderCustomerPrecheck", "Items"]}""");

    private final MongoOperationExecutor executor = new MongoOperationExecutor(
            new MongoDatabaseProvider(Map.of("lendercore", MongoTestSupport.client())));
    private final Instant now = Instant.parse("2026-10-17T10:00:00Z");

    private MongoCollection<Document> prechecks;

    // The Axis Dedupe operations, as in l1-config
    private static final OperationDef UPSERT_INITIATED = op("""
            {"op": "precheck.upsertInitiated", "kind": "MONGO", "resource": "lendercore-mongo",
             "params": {"loanRequestId": {}, "lender": {}, "customerId": {}, "clientLoanId": {}},
             "collection": "LenderCustomerPrecheck", "action": "UPSERT",
             "filter": {"loanRequestId": ":loanRequestId", "lenderSlug": ":lender"},
             "setOnInsert": {"customerId": ":customerId", "precheckStatus": "initiated", "approved": false,
                             "responseMap": {}, "creation_date": ":sys.now", "_class": "com.rupeek.lender.entity.PrecheckEntity"},
             "set": {"client_loan_id": ":clientLoanId", "last_updated": ":sys.now"},
             "inc": {"version": 1}}""");
    private static final OperationDef FIND = op("""
            {"op": "precheck.findByLoanRequest", "kind": "MONGO", "resource": "lendercore-mongo",
             "params": {"loanRequestId": {}, "lender": {}},
             "collection": "LenderCustomerPrecheck", "action": "FIND_ONE",
             "filter": {"loanRequestId": ":loanRequestId", "lenderSlug": ":lender"}}""");
    private static final OperationDef MARK_DONE = op("""
            {"op": "precheck.markDedupeDone", "kind": "MONGO", "resource": "lendercore-mongo",
             "params": {"loanRequestId": {}, "lender": {}, "applicantId": {}},
             "collection": "LenderCustomerPrecheck", "action": "UPDATE_ONE",
             "filter": {"loanRequestId": ":loanRequestId", "lenderSlug": ":lender"},
             "set": {"applicantId": ":applicantId", "precheckStatus": "processed"},
             "inc": {"version": 1}, "expect": {"matched": 1}}""");

    @BeforeEach
    void clean() {
        prechecks = MongoTestSupport.client().getDatabase(DB).getCollection("LenderCustomerPrecheck");
        prechecks.drop();
        MongoTestSupport.client().getDatabase(DB).getCollection("Items").drop();
    }

    private ExecContext ctx() {
        return new ExecContext(RESOURCE, null, Map.of(), Map.of("now", now, "caseId", "case-1", "runNo", 1L), new CallRecorder());
    }

    private Object run(OperationDef def, Map<String, Object> params) throws TransportException {
        return executor.execute(executor.prepare(def), params, ctx()).get("result");
    }

    @Test
    void upsertInsertsOnceThenOnlyUpdates() throws Exception {
        Map<String, Object> params = Map.of("loanRequestId", "LR-1", "lender", "axis", "customerId", "C1", "clientLoanId", "CL-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) run(UPSERT_INITIATED, params);
        assertThat(first.get("matched")).isEqualTo(0L);
        assertThat(first.get("upsertedId")).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) run(UPSERT_INITIATED, params);
        assertThat(second.get("matched")).isEqualTo(1L);
        assertThat(second.get("upsertedId")).isNull();

        Document doc = prechecks.find().first();
        assertThat(prechecks.countDocuments()).isEqualTo(1);
        assertThat(doc.getString("customerId")).isEqualTo("C1");
        assertThat(doc.getString("precheckStatus")).isEqualTo("initiated");
        assertThat(doc.getString("_class")).isEqualTo("com.rupeek.lender.entity.PrecheckEntity");
        assertThat(doc.get("creation_date")).isEqualTo(Date.from(now));   // stored as a BSON date, like Spring Data
        assertThat(doc.getInteger("version")).isEqualTo(2);               // incremented on both calls
    }

    @Test
    void findOneReturnsPlainDataOrNull() throws Exception {
        assertThat(run(FIND, Map.of("loanRequestId", "LR-1", "lender", "axis"))).isNull();

        run(UPSERT_INITIATED, Map.of("loanRequestId", "LR-1", "lender", "axis", "customerId", "C1", "clientLoanId", "CL-1"));
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) run(FIND, Map.of("loanRequestId", "LR-1", "lender", "axis"));

        assertThat(doc.get("_id")).isInstanceOf(String.class);                       // ObjectId -> hex string
        assertThat(doc.get("creation_date")).isEqualTo("2026-10-17T10:00:00Z");     // Date -> ISO string
        assertThat(doc.get("client_loan_id")).isEqualTo("CL-1");
    }

    @Test
    void updateWithExpectFailsWhenNothingMatched() {
        assertThatThrownBy(() -> run(MARK_DONE, Map.of("loanRequestId", "missing", "lender", "axis", "applicantId", "A1")))
                .isInstanceOf(OperationException.class)
                .hasMessageContaining("expected matched=1 but was 0")
                .satisfies(e -> assertThat(((OperationException) e).errorClass()).isEqualTo(ErrorClass.PERMANENT));
    }

    @Test
    void findManyHonoursSortAndLimit() throws Exception {
        MongoCollection<Document> items = MongoTestSupport.client().getDatabase(DB).getCollection("Items");
        for (int i = 1; i <= 5; i++) {
            items.insertOne(new Document("group", "g").append("n", i));
        }
        OperationDef def = op("""
                {"op": "items.top", "kind": "MONGO", "resource": "lendercore-mongo", "params": {"g": {}},
                 "collection": "Items", "action": "FIND_MANY", "filter": {"group": ":g", "n": {"$gte": 2}},
                 "sort": {"n": -1}, "projection": {"_id": 0}, "limit": 2}""");

        assertThat(run(def, Map.of("g", "g"))).isEqualTo(List.of(Map.of("group", "g", "n", 5), Map.of("group", "g", "n", 4)));
    }

    @Test
    void duplicateKeyOnInsertIsPermanent() throws Exception {
        MongoCollection<Document> items = MongoTestSupport.client().getDatabase(DB).getCollection("Items");
        items.createIndex(Indexes.ascending("key"), new IndexOptions().unique(true));
        OperationDef insert = op("""
                {"op": "items.insert", "kind": "MONGO", "resource": "lendercore-mongo", "params": {"k": {}},
                 "collection": "Items", "action": "INSERT", "set": {"key": ":k", "literal": "::not-a-placeholder"}}""");

        run(insert, Map.of("k", "K1"));
        assertThat(items.find().first().getString("literal")).isEqualTo(":not-a-placeholder");
        assertThatThrownBy(() -> run(insert, Map.of("k", "K1")))
                .isInstanceOf(OperationException.class).hasMessageContaining("duplicate key");
    }

    @Test
    void collectionsOutsideTheAllowlistAreRefused() {
        OperationDef def = op("""
                {"op": "x", "kind": "MONGO", "resource": "lendercore-mongo", "params": {"id": {}},
                 "collection": "Secrets", "action": "FIND_ONE", "filter": {"id": ":id"}}""");
        assertThatThrownBy(() -> run(def, Map.of("id", "1")))
                .isInstanceOf(OperationException.class).hasMessageContaining("allowlist");
    }

    @Test
    void unsafeOrInvalidDefinitionsFailAtPrepare() {
        Map<String, String> bad = new HashMap<>();
        bad.put("$where in filter", """
                {"op": "x", "kind": "MONGO", "resource": "r", "collection": "c", "action": "FIND_ONE",
                 "filter": {"$where": "this.a == 1"}}""");
        bad.put("$expr nested", """
                {"op": "x", "kind": "MONGO", "resource": "r", "collection": "c", "action": "FIND_ONE",
                 "filter": {"$or": [{"$expr": {"$eq": ["$a", 1]}}]}}""");
        bad.put("operator inside set", """
                {"op": "x", "kind": "MONGO", "resource": "r", "collection": "c", "action": "UPDATE_ONE",
                 "filter": {"a": 1}, "set": {"$unset": "b"}}""");
        bad.put("undeclared placeholder", """
                {"op": "x", "kind": "MONGO", "resource": "r", "collection": "c", "action": "FIND_ONE",
                 "filter": {"a": ":notAParam"}}""");
        bad.put("unknown sys value", """
                {"op": "x", "kind": "MONGO", "resource": "r", "collection": "c", "action": "FIND_ONE",
                 "filter": {"a": ":sys.secret"}}""");
        bad.put("setOnInsert without upsert", """
                {"op": "x", "kind": "MONGO", "resource": "r", "collection": "c", "action": "UPDATE_ONE",
                 "filter": {"a": 1}, "setOnInsert": {"b": 1}}""");
        bad.put("find many without limit", """
                {"op": "x", "kind": "MONGO", "resource": "r", "collection": "c", "action": "FIND_MANY", "filter": {"a": 1}}""");
        bad.put("empty filter", """
                {"op": "x", "kind": "MONGO", "resource": "r", "collection": "c", "action": "UPDATE_ONE",
                 "filter": {}, "set": {"b": 1}}""");
        bad.put("unknown action", """
                {"op": "x", "kind": "MONGO", "resource": "r", "collection": "c", "action": "DELETE_MANY", "filter": {"a": 1}}""");
        bad.forEach((name, json) -> assertThatThrownBy(() -> executor.prepare(op(json))).as(name)
                .isInstanceOf(ConfigException.class));
    }

    @Test
    void unreachableMongoIsATransportFailure() {
        MongoClient dead = MongoClients.create("mongodb://localhost:1/?serverSelectionTimeoutMS=300&connectTimeoutMS=300");
        try (dead) {
            MongoOperationExecutor offline = new MongoOperationExecutor(new MongoDatabaseProvider(Map.of("lendercore", dead)));
            assertThatThrownBy(() -> offline.execute(offline.prepare(FIND), Map.of("loanRequestId", "LR", "lender", "axis"), ctx()))
                    .isInstanceOf(TransportException.class)
                    .satisfies(e -> assertThat(((TransportException) e).timeout()).isTrue());
        }
    }
}
