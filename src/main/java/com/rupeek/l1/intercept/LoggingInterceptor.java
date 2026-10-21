package com.rupeek.l1.intercept;

import com.rupeek.l1.ops.CallRecorder;
import com.rupeek.l1.ops.OperationException;
import com.rupeek.l1.ops.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * One log line per operation call: kind, HTTP method and URL path (query dropped) or Mongo action and collection,
 * the result and the time taken. Never logs bodies, parameters or documents, so no customer data reaches the log.
 */
public final class LoggingInterceptor implements OperationInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public Map<String, Object> around(OperationCall call, Proceed next) throws TransportException {
        long start = System.nanoTime();
        String result = "?";
        try {
            Map<String, Object> out = next.proceed();
            result = summary(call, out);
            return out;
        } catch (TransportException e) {
            result = (e.sent() ? "sent, " : "not sent, ") + (e.timeout() ? "timeout" : "transport error");
            throw e;
        } catch (OperationException e) {
            result = e.errorClass().name() + (e.httpStatus() == null ? "" : " (HTTP " + e.httpStatus() + ")");
            throw e;
        } catch (RuntimeException e) {
            result = "error " + e.getClass().getSimpleName();
            throw e;
        } finally {
            try {
                LOG.info("[L1 {}]   {} │   {} {} → {} ({} ms)", call.caseId(), call.recipeRef(), call.def().kind(),
                        target(call), result, (System.nanoTime() - start) / 1_000_000);
            } catch (RuntimeException ignored) {
                // logging must never fail a call
            }
        }
    }

    private static String target(OperationCall call) {
        CallRecorder r = call.ctx().recorder();
        if (r.method() == null) {
            return call.opRef();
        }
        String t = r.target() == null ? "" : r.target();
        int q = t.indexOf('?');
        return r.method() + " " + (q < 0 ? t : t.substring(0, q)) + "  [" + call.opRef() + "]";
    }

    private static String summary(OperationCall call, Map<String, Object> out) {
        if (out.get("resp") instanceof Map<?, ?> resp) {
            return "HTTP " + resp.get("status");
        }
        Object result = out.get("result");
        return switch (call.def().kind()) {
            case MONGO -> result instanceof Map<?, ?> m && (m.containsKey("matched") || m.containsKey("inserted"))
                    ? m.toString()
                    : result instanceof List<?> l ? l.size() + " document(s)"
                    : result == null ? "not found" : "found";
            case GEN, MAP -> "ok";
            default -> "ok";
        };
    }
}
