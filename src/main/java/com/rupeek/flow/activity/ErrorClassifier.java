package com.rupeek.flow.activity;

import com.rupeek.flow.model.OperationKind;

import java.util.Locale;
import java.util.Set;

/**
 * Default failure classification (a node's own {@code classify} expression, in {@code fields}, overrides the
 * response rules below). Flow-native port of {@code com.rupeek.l1.engine.ErrorClassifier}'s rules, with
 * {@code com.rupeek.l1.ops.ErrorClass} collapsed into {@link TaskFailureType} directly (they were a 1:1 mapping
 * in L1 anyway — {@code ErrorClass.failureType()}).
 *
 * <ul>
 *   <li>Not sent (connect refused / connect timeout): TECHNICAL.</li>
 *   <li>Sent but no reply: TECHNICAL if idempotent, else UNKNOWN.</li>
 *   <li>HTTP 429, 502, 503, 504: TECHNICAL. Other 5xx: TECHNICAL if idempotent, else UNKNOWN.</li>
 *   <li>Other 4xx, or 2xx with {@code success} false: BUSINESS.</li>
 *   <li>A non-HTTP node whose {@code success} is false: PERMANENT.</li>
 * </ul>
 * Idempotent means: {@code fields.idempotent == true}, an HTTP GET/HEAD, or a MONGO/MAP/GEN node (Mongo writes
 * are expected to be upserts/guarded updates by convention, same as L1).
 */
public final class ErrorClassifier {

    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 503, 504);

    private ErrorClassifier() {
    }

    public static TaskFailureType forTransport(boolean idempotent, boolean sent) {
        if (!sent) {
            return TaskFailureType.TECHNICAL;
        }
        return idempotent ? TaskFailureType.TECHNICAL : TaskFailureType.UNKNOWN;
    }

    public static TaskFailureType forFailedSuccess(OperationKind kind, Integer httpStatus, boolean idempotent) {
        if (kind != OperationKind.HTTP || httpStatus == null) {
            return TaskFailureType.PERMANENT;
        }
        if (RETRYABLE_STATUSES.contains(httpStatus)) {
            return TaskFailureType.TECHNICAL;
        }
        if (httpStatus >= 500) {
            return idempotent ? TaskFailureType.TECHNICAL : TaskFailureType.UNKNOWN;
        }
        return TaskFailureType.BUSINESS;
    }

    /** Parses a {@code classify} expression result; anything else is a config error. */
    public static TaskFailureType parse(Object classifyResult) {
        if (classifyResult instanceof String s) {
            try {
                return TaskFailureType.valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        throw new FlowTaskFailure(TaskFailureType.PERMANENT,
                "classify must return one of TECHNICAL, BUSINESS, UNKNOWN, PERMANENT, got " + classifyResult);
    }

    public static boolean isIdempotentHttp(boolean declaredIdempotent, String method) {
        if (declaredIdempotent) {
            return true;
        }
        String m = method == null ? "" : method.toUpperCase(Locale.ROOT);
        return m.equals("GET") || m.equals("HEAD");
    }
}
