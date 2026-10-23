package com.rupeek.flow.task;

import com.rupeek.flow.task.executor.gen.GenTaskExecutor;
import com.rupeek.flow.task.executor.http.HttpTaskExecutor;
import com.rupeek.flow.task.executor.map.MapTaskExecutor;
import com.rupeek.flow.task.executor.mongo.MongoTaskExecutor;
import com.rupeek.flow.model.OperationKind;
import io.temporal.failure.ApplicationFailure;

/**
 * The real, registered {@link TaskActivity}: dispatches on {@link TaskRequest#kind()} to the matching native
 * executor (HTTP/MONGO/MAP/GEN).
 *
 * <p>Does NOT blanket-mask outputs by field name: an earlier version of this class ran every output through
 * {@code PiiMasker.maskPayload}, keyed off {@code PiiMasker.GLOBAL_KEYS} (which includes the generic word
 * {@code "pan"}) — this silently corrupted Axis's dedupe/PAN-match status flag ({@code Y}/{@code WIP}/{@code N},
 * a field also named {@code pan}, nothing to do with a PAN card number) into a masked placeholder. L1 never
 * masked an operation's own output values at all — {@code PiiMasker} there is used only when building
 * {@code LenderApiAuditLog} rows (see {@link com.rupeek.flow.audit.TaskAuditor}), via each node's explicit
 * {@code piiPaths}, never a blanket key-name scan over business data. PII protection on workflow outputs stays
 * exactly as narrow as L1's real guarantee: nodes simply don't declare PII fields as outputs.
 */
public final class TaskActivityImpl implements TaskActivity {

    private final HttpTaskExecutor http;
    private final MongoTaskExecutor mongo;
    private final MapTaskExecutor map;
    private final GenTaskExecutor gen;

    public TaskActivityImpl(HttpTaskExecutor http, MongoTaskExecutor mongo, MapTaskExecutor map, GenTaskExecutor gen) {
        this.http = http;
        this.mongo = mongo;
        this.map = map;
        this.gen = gen;
    }

    @Override
    public TaskResult execute(TaskRequest request) {
        try {
            return dispatch(request);
        } catch (FlowTaskFailure f) {
            throw ApplicationFailure.newFailure(f.getMessage(), f.type().name(), f.getCause());
        }
    }

    private TaskResult dispatch(TaskRequest request) {
        OperationKind kind = request.kind();
        return switch (kind) {
            case HTTP -> http.execute(request);
            case MONGO -> mongo.execute(request);
            case MAP -> map.execute(request);
            case GEN -> gen.execute(request);
            default -> throw new FlowTaskFailure(TaskFailureType.PERMANENT, "no native executor for kind " + kind);
        };
    }
}
