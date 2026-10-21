package com.rupeek.l1.ops.mongo;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.MongoNodeIsRecoveringException;
import com.mongodb.MongoNotPrimaryException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.rupeek.l1.model.ConfigJson.ConfigException;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.OperationKind;
import com.rupeek.l1.model.Resource;
import com.rupeek.l1.ops.ErrorClass;
import com.rupeek.l1.ops.ExecContext;
import com.rupeek.l1.ops.OperationException;
import com.rupeek.l1.ops.OperationExecutor;
import com.rupeek.l1.ops.PreparedOperation;
import com.rupeek.l1.ops.TransportException;
import org.bson.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code MONGO} operations with fixed shapes: {@code FIND_ONE}, {@code FIND_MANY}, {@code INSERT},
 * {@code UPDATE_ONE}, {@code UPSERT}. Returns {@code {result: ...}}: the document (or list, or null) for reads,
 * {@code {matched, modified, upsertedId}} or {@code {inserted, insertedId}} for writes.
 *
 * <p>{@code INSERT} inserts the bound {@code set} document. {@code setOnInsert} is only valid with {@code UPSERT}.
 * {@code expect} compares result counts (e.g. {@code {"matched": 1}}); a mismatch is {@code PERMANENT}.
 */
public final class MongoOperationExecutor implements OperationExecutor {

    enum Action { FIND_ONE, FIND_MANY, INSERT, UPDATE_ONE, UPSERT }

    static final int MAX_FIND_MANY = 100;

    private final MongoDatabaseProvider databases;

    public MongoOperationExecutor(MongoDatabaseProvider databases) {
        this.databases = databases;
    }

    @Override
    public OperationKind kind() {
        return OperationKind.MONGO;
    }

    record PreparedMongo(OperationDef def, Action action) implements PreparedOperation {
    }

    @Override
    public PreparedOperation prepare(OperationDef def) {
        String where = def.op();
        if (def.resource() == null) {
            throw new ConfigException(where + ": MONGO operation needs a 'resource'");
        }
        if (def.collection() == null || def.collection().isBlank()) {
            throw new ConfigException(where + ": MONGO operation needs a 'collection'");
        }
        Action action;
        try {
            action = Action.valueOf(String.valueOf(def.action()));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(where + ".action: must be one of " + List.of(Action.values()) + ", got " + def.action());
        }
        boolean isRead = action == Action.FIND_ONE || action == Action.FIND_MANY;
        if (action != Action.INSERT && (def.filter() == null || def.filter().isEmpty())) {
            throw new ConfigException(where + ".filter: required and non-empty for " + action);
        }
        if (isRead && (notEmpty(def.set()) || notEmpty(def.setOnInsert()) || notEmpty(def.inc()))) {
            throw new ConfigException(where + ": " + action + " cannot have set / setOnInsert / inc");
        }
        if (!isRead && (notEmpty(def.sort()) || notEmpty(def.projection()) || def.limit() != null)) {
            throw new ConfigException(where + ": " + action + " cannot have sort / projection / limit");
        }
        if (action == Action.FIND_MANY && (def.limit() == null || def.limit() < 1 || def.limit() > MAX_FIND_MANY)) {
            throw new ConfigException(where + ".limit: FIND_MANY needs a limit between 1 and " + MAX_FIND_MANY);
        }
        if (action == Action.INSERT && !notEmpty(def.set())) {
            throw new ConfigException(where + ".set: INSERT needs the document in 'set'");
        }
        if ((action == Action.UPDATE_ONE || action == Action.UPSERT)
                && !notEmpty(def.set()) && !notEmpty(def.setOnInsert()) && !notEmpty(def.inc())) {
            throw new ConfigException(where + ": " + action + " needs set, setOnInsert or inc");
        }
        if (notEmpty(def.setOnInsert()) && action != Action.UPSERT) {
            throw new ConfigException(where + ".setOnInsert: only valid with UPSERT");
        }
        Set<String> params = def.params().keySet();
        MongoQueryBinder.validate(def.filter(), where + ".filter", true, params);
        MongoQueryBinder.validate(def.set(), where + ".set", false, params);
        MongoQueryBinder.validate(def.setOnInsert(), where + ".setOnInsert", false, params);
        MongoQueryBinder.validate(def.inc(), where + ".inc", false, params);
        MongoQueryBinder.validate(def.sort(), where + ".sort", false, params);
        MongoQueryBinder.validate(def.projection(), where + ".projection", false, params);
        return new PreparedMongo(def, action);
    }

    @Override
    public Map<String, Object> execute(PreparedOperation prepared, Map<String, Object> params, ExecContext ctx)
            throws TransportException {
        PreparedMongo op = (PreparedMongo) prepared;
        OperationDef def = op.def();
        Resource resource = ctx.resource();
        if (resource == null) {
            throw new OperationException(ErrorClass.PERMANENT, def.op() + ": no Mongo resource resolved");
        }
        if (!resource.collections().contains(def.collection())) {
            throw new OperationException(ErrorClass.PERMANENT, def.op() + ": collection '" + def.collection()
                    + "' is not in the allowlist of resource '" + resource.resource() + "'");
        }
        Document filter = MongoQueryBinder.bind(def.filter(), params, ctx.sys());
        Map<String, Object> recordedRequest = new LinkedHashMap<>();
        recordedRequest.put("filter", filter);
        try {
            MongoCollection<Document> collection = databases.database(resource.datasourceRef(), resource.database())
                    .getCollection(def.collection());
            Object result = switch (op.action()) {
                case FIND_ONE -> {
                    ctx.recorder().request(op.action().name(), target(resource, def), Map.of(), recordedRequest);
                    yield BsonPlain.toPlain(find(collection, filter, def, params, ctx).first());
                }
                case FIND_MANY -> {
                    ctx.recorder().request(op.action().name(), target(resource, def), Map.of(), recordedRequest);
                    List<Object> rows = new ArrayList<>();
                    find(collection, filter, def, params, ctx).limit(def.limit()).forEach(d -> rows.add(BsonPlain.toPlain(d)));
                    yield rows;
                }
                case INSERT -> {
                    Document doc = MongoQueryBinder.bind(def.set(), params, ctx.sys());
                    recordedRequest.put("document", doc);
                    ctx.recorder().request(op.action().name(), target(resource, def), Map.of(), recordedRequest);
                    InsertOneResult r = collection.insertOne(doc);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("inserted", r.wasAcknowledged() ? 1L : 0L);
                    out.put("insertedId", r.getInsertedId() == null ? null : BsonPlain.toPlain(r.getInsertedId()));
                    yield out;
                }
                case UPDATE_ONE, UPSERT -> {
                    Document update = new Document();
                    putIfPresent(update, "$set", MongoQueryBinder.bind(def.set(), params, ctx.sys()));
                    putIfPresent(update, "$setOnInsert", MongoQueryBinder.bind(def.setOnInsert(), params, ctx.sys()));
                    putIfPresent(update, "$inc", MongoQueryBinder.bind(def.inc(), params, ctx.sys()));
                    recordedRequest.put("update", update);
                    ctx.recorder().request(op.action().name(), target(resource, def), Map.of(), recordedRequest);
                    UpdateResult r = collection.updateOne(filter, update,
                            new UpdateOptions().upsert(op.action() == Action.UPSERT));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("matched", r.getMatchedCount());
                    out.put("modified", r.getModifiedCount());
                    out.put("upsertedId", r.getUpsertedId() == null ? null : BsonPlain.toPlain(r.getUpsertedId()));
                    yield out;
                }
            };
            ctx.recorder().response(null, result);
            checkExpect(def, result);
            Map<String, Object> scope = new HashMap<>();
            scope.put("result", result);
            return scope;
        } catch (MongoSocketException | MongoTimeoutException | MongoNotPrimaryException
                 | MongoNodeIsRecoveringException | MongoExecutionTimeoutException e) {
            boolean timeout = e instanceof MongoTimeoutException || e instanceof MongoExecutionTimeoutException;
            throw new TransportException(true, timeout, def.op() + ": Mongo unavailable: " + e.getMessage(), e);
        } catch (MongoWriteException e) {
            String reason = e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY ? "duplicate key" : e.getMessage();
            throw new OperationException(ErrorClass.PERMANENT, def.op() + ": write rejected: " + reason, null, e);
        } catch (MongoException e) {
            throw new OperationException(ErrorClass.PERMANENT, def.op() + ": Mongo error: " + e.getMessage(), null, e);
        }
    }

    private static FindIterable<Document> find(MongoCollection<Document> collection, Document filter, OperationDef def,
                                               Map<String, Object> params, ExecContext ctx) {
        FindIterable<Document> it = collection.find(filter);
        if (notEmpty(def.projection())) {
            it = it.projection(MongoQueryBinder.bind(def.projection(), params, ctx.sys()));
        }
        if (notEmpty(def.sort())) {
            it = it.sort(MongoQueryBinder.bind(def.sort(), params, ctx.sys()));
        }
        return it;
    }

    private static void checkExpect(OperationDef def, Object result) {
        if (def.expect() == null || def.expect().isEmpty()) {
            return;
        }
        if (!(result instanceof Map<?, ?> counts)) {
            throw new OperationException(ErrorClass.PERMANENT, def.op() + ": 'expect' only applies to write results");
        }
        def.expect().forEach((key, expected) -> {
            Object actual = counts.get(key);
            boolean same = actual instanceof Number a && expected instanceof Number e && a.longValue() == e.longValue();
            if (!same) {
                throw new OperationException(ErrorClass.PERMANENT,
                        def.op() + ": expected " + key + "=" + expected + " but was " + actual);
            }
        });
    }

    private static String target(Resource resource, OperationDef def) {
        return "mongo://" + resource.database() + "/" + def.collection();
    }

    private static void putIfPresent(Document update, String operator, Document part) {
        if (!part.isEmpty()) {
            update.put(operator, part);
        }
    }

    private static boolean notEmpty(Map<String, Object> m) {
        return m != null && !m.isEmpty();
    }
}
