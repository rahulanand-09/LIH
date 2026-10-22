package com.rupeek.flow.activity.mongo;

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
import com.rupeek.flow.activity.AuditFields;
import com.rupeek.flow.activity.ErrorClassifier;
import com.rupeek.flow.activity.FlowTaskFailure;
import com.rupeek.flow.activity.TaskFailureType;
import com.rupeek.flow.activity.TaskRequest;
import com.rupeek.flow.activity.TaskResult;
import com.rupeek.flow.audit.TaskAuditor;
import com.rupeek.flow.audit.TaskCall;
import com.rupeek.flow.expr.ExpressionEngine;
import com.rupeek.flow.expr.ScopeDecl;
import com.rupeek.flow.resource.Resource;
import com.rupeek.flow.resource.ResourceProvider;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code MONGO} nodes with fixed shapes: {@code FIND_ONE}, {@code FIND_MANY}, {@code INSERT}, {@code UPDATE_ONE},
 * {@code UPSERT}. Flow-native port of {@code com.rupeek.l1.ops.mongo.MongoOperationExecutor}, with the recipe
 * layer's {@code out}/{@code required} evaluation folded straight in (there is no separate recipe step here —
 * the node's own {@code fields.out} plays that role, evaluated against {@code {inputs, result}}).
 *
 * <p>{@code fields}: {@code resource}, {@code collection}, {@code action}, {@code filter}, {@code set},
 * {@code setOnInsert}, {@code inc}, {@code sort}, {@code projection}, {@code limit}, {@code expect}, {@code out}
 * (CEL, against {@code {inputs, result}}), optional {@code audit} (literal or {@code =CEL}, against
 * {@code {inputs, env}}).
 */
public final class MongoTaskExecutor {

    private enum Action { FIND_ONE, FIND_MANY, INSERT, UPDATE_ONE, UPSERT }

    private static final int MAX_FIND_MANY = 100;

    private final ResourceProvider resources;
    private final MongoDatabaseProvider databases;
    private final ExpressionEngine expressions;
    private final TaskAuditor auditor;

    public MongoTaskExecutor(ResourceProvider resources, MongoDatabaseProvider databases, ExpressionEngine expressions,
                             TaskAuditor auditor) {
        this.resources = resources;
        this.databases = databases;
        this.expressions = expressions;
        this.auditor = auditor;
    }

    /**
     * Checks a MONGO node's {@code fields} without touching the database: the fixed-shape document checks
     * {@link #validateShape} and {@link MongoQueryBinder#validate} already run (pure, no I/O), plus compiling
     * {@code out.*} (CEL, {@link ScopeDecl#RESULT}) — the one place this kind's config carries CEL. {@code filter}/
     * {@code set}/etc. use {@link MongoQueryBinder}'s {@code :name} placeholders, not CEL, so there is nothing
     * more to compile there. Called both from {@link #execute} and a publish-time check (see
     * {@code com.rupeek.flow.registry.TaskPublisher}).
     */
    public void validate(Map<String, Object> fields) {
        String nodeId = "validate";
        if (!(fields.get("resource") instanceof String)) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ".fields.resource: is required");
        }
        if (!(fields.get("collection") instanceof String collectionName) || collectionName.isBlank()) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ".fields.collection: is required");
        }
        Action action = parseAction(nodeId, fields.get("action"));
        Map<String, Object> filter = mapField(fields, "filter");
        Map<String, Object> set = mapField(fields, "set");
        Map<String, Object> setOnInsert = mapField(fields, "setOnInsert");
        Map<String, Object> inc = mapField(fields, "inc");
        Map<String, Object> sort = mapField(fields, "sort");
        Map<String, Object> projection = mapField(fields, "projection");
        Integer limit = fields.get("limit") instanceof Number n ? n.intValue() : null;
        validateShape(nodeId, action, filter, set, setOnInsert, inc, sort, projection, limit);
        MongoQueryBinder.validate(filter, nodeId + ".fields.filter", true);
        MongoQueryBinder.validate(set, nodeId + ".fields.set", false);
        MongoQueryBinder.validate(setOnInsert, nodeId + ".fields.setOnInsert", false);
        MongoQueryBinder.validate(inc, nodeId + ".fields.inc", false);
        MongoQueryBinder.validate(sort, nodeId + ".fields.sort", false);
        MongoQueryBinder.validate(projection, nodeId + ".fields.projection", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> outExprs = (Map<String, Object>) fields.getOrDefault("out", Map.of());
        outExprs.forEach((name, expr) ->
                expressions.compile(String.valueOf(expr), ScopeDecl.RESULT, nodeId + ".fields.out." + name));
    }

    public TaskResult execute(TaskRequest request) {
        Map<String, Object> fields = request.fields();
        String nodeId = request.nodeId();
        validate(fields);

        String resourceRef = (String) fields.get("resource");
        String collectionName = (String) fields.get("collection");
        Action action = parseAction(nodeId, fields.get("action"));

        Map<String, Object> filter = mapField(fields, "filter");
        Map<String, Object> set = mapField(fields, "set");
        Map<String, Object> setOnInsert = mapField(fields, "setOnInsert");
        Map<String, Object> inc = mapField(fields, "inc");
        Map<String, Object> sort = mapField(fields, "sort");
        Map<String, Object> projection = mapField(fields, "projection");
        Integer limit = fields.get("limit") instanceof Number n ? n.intValue() : null;

        Resource resource = resources.resource(resourceRef);
        if (!resource.collections().contains(collectionName)) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT,
                    nodeId + ": collection '" + collectionName + "' is not in the allowlist of resource '" + resourceRef + "'");
        }
        Map<String, Object> sys = sys(request);
        Document boundFilter = MongoQueryBinder.bind(filter, request.inputs(), sys);

        String requestType = AuditFields.requestType(fields, request.inputs(), Map.of(), expressions, nodeId);
        String target = "mongo://" + resource.database() + "/" + collectionName;
        String loanRequestId = AuditFields.auditKey(fields, "loanRequestId", request.inputs(), expressions, nodeId);
        String customerId = AuditFields.auditKey(fields, "customerId", request.inputs(), expressions, nodeId);
        List<String> piiPaths = AuditFields.piiPaths(fields);
        Instant invokedAt = Instant.now();
        long start = System.currentTimeMillis();

        Object result;
        try {
            MongoCollection<Document> collection = databases.database(resource.datasourceRef(), resource.database())
                    .getCollection(collectionName);
            result = switch (action) {
                case FIND_ONE -> BsonPlain.toPlain(find(collection, boundFilter, sort, projection, request.inputs(), sys).first());
                case FIND_MANY -> {
                    List<Object> rows = new ArrayList<>();
                    find(collection, boundFilter, sort, projection, request.inputs(), sys).limit(limit)
                            .forEach(d -> rows.add(BsonPlain.toPlain(d)));
                    yield rows;
                }
                case INSERT -> {
                    Document doc = MongoQueryBinder.bind(set, request.inputs(), sys);
                    InsertOneResult r = collection.insertOne(doc);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("inserted", r.wasAcknowledged() ? 1L : 0L);
                    out.put("insertedId", r.getInsertedId() == null ? null : BsonPlain.toPlain(r.getInsertedId()));
                    yield out;
                }
                case UPDATE_ONE, UPSERT -> {
                    Document update = new Document();
                    putIfPresent(update, "$set", MongoQueryBinder.bind(set, request.inputs(), sys));
                    putIfPresent(update, "$setOnInsert", MongoQueryBinder.bind(setOnInsert, request.inputs(), sys));
                    putIfPresent(update, "$inc", MongoQueryBinder.bind(inc, request.inputs(), sys));
                    UpdateResult r = collection.updateOne(boundFilter, update,
                            new UpdateOptions().upsert(action == Action.UPSERT));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("matched", r.getMatchedCount());
                    out.put("modified", r.getModifiedCount());
                    out.put("upsertedId", r.getUpsertedId() == null ? null : BsonPlain.toPlain(r.getUpsertedId()));
                    yield out;
                }
            };
        } catch (MongoSocketException | MongoTimeoutException | MongoNotPrimaryException
                 | MongoNodeIsRecoveringException | MongoExecutionTimeoutException e) {
            // Mongo ops are always treated as idempotent (upserts/guarded updates, by convention), so a
            // transport-ish failure is always TECHNICAL regardless of whether a reply was ever received.
            TaskFailureType type = ErrorClassifier.forTransport(true, true);
            String status = e instanceof MongoTimeoutException || e instanceof MongoExecutionTimeoutException ? "TIMEOUT" : "TECHNICAL_FAILURE";
            auditFailure(requestType, nodeId, request, fields, target, loanRequestId, customerId, piiPaths, boundFilter,
                    status, e.getMessage(), invokedAt, start);
            throw new FlowTaskFailure(type, nodeId + ": Mongo unavailable: " + e.getMessage(), e);
        } catch (MongoWriteException e) {
            String reason = e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY ? "duplicate key" : e.getMessage();
            auditFailure(requestType, nodeId, request, fields, target, loanRequestId, customerId, piiPaths, boundFilter,
                    "TECHNICAL_FAILURE", reason, invokedAt, start);
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ": write rejected: " + reason, e);
        } catch (MongoException e) {
            auditFailure(requestType, nodeId, request, fields, target, loanRequestId, customerId, piiPaths, boundFilter,
                    "TECHNICAL_FAILURE", e.getMessage(), invokedAt, start);
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ": Mongo error: " + e.getMessage(), e);
        }

        if (requestType != null) {
            TaskCall call = new TaskCall(request.caseId(), nodeId, request.taskRef(), AuditFields.lender(fields),
                    loanRequestId, customerId, requestType, target, boundFilter, null, result, piiPaths);
            auditor.recordSuccess(call, invokedAt, System.currentTimeMillis() - start);
        }

        checkExpect(nodeId, mapField(fields, "expect"), result);

        Map<String, Object> vars = new HashMap<>();
        vars.put("inputs", request.inputs());
        vars.put("result", result);
        Map<String, Object> outputs = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> outExprs = (Map<String, Object>) fields.getOrDefault("out", Map.of());
        outExprs.forEach((name, expr) -> outputs.put(name,
                expressions.eval(expressions.compile(String.valueOf(expr), ScopeDecl.RESULT, nodeId + ".fields.out." + name), vars)));
        return new TaskResult("OK", outputs);
    }

    private static FindIterable<Document> find(MongoCollection<Document> collection, Document filter,
                                               Map<String, Object> sort, Map<String, Object> projection,
                                               Map<String, Object> inputs, Map<String, Object> sys) {
        FindIterable<Document> it = collection.find(filter);
        if (projection != null && !projection.isEmpty()) {
            it = it.projection(MongoQueryBinder.bind(projection, inputs, sys));
        }
        if (sort != null && !sort.isEmpty()) {
            it = it.sort(MongoQueryBinder.bind(sort, inputs, sys));
        }
        return it;
    }

    private static void checkExpect(String nodeId, Map<String, Object> expect, Object result) {
        if (expect == null || expect.isEmpty()) {
            return;
        }
        if (!(result instanceof Map<?, ?> counts)) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ": 'expect' only applies to write results");
        }
        expect.forEach((key, expected) -> {
            Object actual = counts.get(key);
            boolean same = actual instanceof Number a && expected instanceof Number e && a.longValue() == e.longValue();
            if (!same) {
                throw new FlowTaskFailure(TaskFailureType.PERMANENT,
                        nodeId + ": expected " + key + "=" + expected + " but was " + actual);
            }
        });
    }

    private static Action parseAction(String nodeId, Object actionValue) {
        try {
            return Action.valueOf(String.valueOf(actionValue));
        } catch (IllegalArgumentException e) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT,
                    nodeId + ".fields.action: must be one of " + List.of(Action.values()) + ", got " + actionValue);
        }
    }

    private static void validateShape(String nodeId, Action action, Map<String, Object> filter, Map<String, Object> set,
                                      Map<String, Object> setOnInsert, Map<String, Object> inc, Map<String, Object> sort,
                                      Map<String, Object> projection, Integer limit) {
        boolean isRead = action == Action.FIND_ONE || action == Action.FIND_MANY;
        if (action != Action.INSERT && (filter == null || filter.isEmpty())) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ".fields.filter: required and non-empty for " + action);
        }
        if (isRead && (notEmpty(set) || notEmpty(setOnInsert) || notEmpty(inc))) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ": " + action + " cannot have set / setOnInsert / inc");
        }
        if (!isRead && (notEmpty(sort) || notEmpty(projection) || limit != null)) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ": " + action + " cannot have sort / projection / limit");
        }
        if (action == Action.FIND_MANY && (limit == null || limit < 1 || limit > MAX_FIND_MANY)) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ".fields.limit: FIND_MANY needs a limit between 1 and " + MAX_FIND_MANY);
        }
        if (action == Action.INSERT && !notEmpty(set)) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ".fields.set: INSERT needs the document in 'set'");
        }
        if ((action == Action.UPDATE_ONE || action == Action.UPSERT) && !notEmpty(set) && !notEmpty(setOnInsert) && !notEmpty(inc)) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ": " + action + " needs set, setOnInsert or inc");
        }
        if (notEmpty(setOnInsert) && action != Action.UPSERT) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, nodeId + ".fields.setOnInsert: only valid with UPSERT");
        }
    }

    private static boolean notEmpty(Map<String, Object> m) {
        return m != null && !m.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapField(Map<String, Object> fields, String key) {
        Object v = fields.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private static void putIfPresent(Document update, String operator, Document part) {
        if (!part.isEmpty()) {
            update.put(operator, part);
        }
    }

    private void auditFailure(String requestType, String nodeId, TaskRequest request, Map<String, Object> fields,
                              String target, String loanRequestId, String customerId, List<String> piiPaths,
                              Object requestBody, String status, String message, Instant invokedAt, long start) {
        if (requestType == null) {
            return;
        }
        TaskCall call = new TaskCall(request.caseId(), nodeId, request.taskRef(), AuditFields.lender(fields),
                loanRequestId, customerId, requestType, target, requestBody, null, null, piiPaths);
        auditor.recordFailure(call, status, message, List.of(), invokedAt, System.currentTimeMillis() - start);
    }

    private static Map<String, Object> sys(TaskRequest request) {
        Map<String, Object> sys = new HashMap<>();
        sys.put("now", Instant.now());
        sys.put("caseId", request.caseId());
        sys.put("runNo", request.runNo());
        return sys;
    }
}
