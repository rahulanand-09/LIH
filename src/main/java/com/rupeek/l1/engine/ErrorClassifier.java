package com.rupeek.l1.engine;

import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.OperationKind;
import com.rupeek.l1.ops.ErrorClass;
import com.rupeek.l1.ops.TransportException;

import java.util.Locale;
import java.util.Set;

/**
 * Default failure classification (an operation's {@code classify} expression overrides the response rules).
 *
 * <ul>
 *   <li>Not sent (connect refused / connect timeout): TECHNICAL.</li>
 *   <li>Sent but no reply: TECHNICAL if idempotent, else UNKNOWN.</li>
 *   <li>HTTP 429, 502, 503, 504: TECHNICAL. Other 5xx: TECHNICAL if idempotent, else UNKNOWN.</li>
 *   <li>Other 4xx, or 2xx with {@code success} false: BUSINESS.</li>
 *   <li>A non-HTTP operation whose {@code success} is false: PERMANENT.</li>
 * </ul>
 * Idempotent means: declared {@code idempotent: true}, an HTTP GET/HEAD, or a MONGO/MAP/GEN operation (Mongo writes
 * in recipes are upserts and guarded updates by rule).
 */
public final class ErrorClassifier {

    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 503, 504);

    public ErrorClass forTransport(OperationDef def, TransportException e) {
        if (!e.sent()) {
            return ErrorClass.TECHNICAL;
        }
        return isIdempotent(def) ? ErrorClass.TECHNICAL : ErrorClass.UNKNOWN;
    }

    public ErrorClass forFailedSuccess(OperationDef def, Integer httpStatus) {
        if (def.kind() != OperationKind.HTTP || httpStatus == null) {
            return ErrorClass.PERMANENT;
        }
        if (RETRYABLE_STATUSES.contains(httpStatus)) {
            return ErrorClass.TECHNICAL;
        }
        if (httpStatus >= 500) {
            return isIdempotent(def) ? ErrorClass.TECHNICAL : ErrorClass.UNKNOWN;
        }
        return ErrorClass.BUSINESS;
    }

    /** Parses a {@code classify} result; anything that is not an error class name is a config error. */
    public ErrorClass parse(Object classifyResult, String configPath) {
        if (classifyResult instanceof String s) {
            try {
                return ErrorClass.valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        throw L1Failure.permanent(configPath + ": classify must return one of TECHNICAL, BUSINESS, UNKNOWN, "
                + "PERMANENT, got " + classifyResult, null);
    }

    static boolean isIdempotent(OperationDef def) {
        if (def.idempotent() || def.kind() != OperationKind.HTTP) {
            return true;
        }
        String method = def.request() == null ? "" : String.valueOf(def.request().method()).toUpperCase(Locale.ROOT);
        return method.equals("GET") || method.equals("HEAD");
    }
}
