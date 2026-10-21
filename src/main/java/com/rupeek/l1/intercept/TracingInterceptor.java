package com.rupeek.l1.intercept;

import com.rupeek.l1.ops.TransportException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * Wraps each operation call in a Micrometer observation ({@code lih.l1.operation.call}), which becomes a span
 * when a tracing bridge is on the classpath, and puts the call's identifiers into the logging MDC:
 * {@code caseId}, {@code recipe}, {@code recipeVersion}, {@code stepId}, {@code opRef}, {@code lender},
 * {@code loanRequestId}. The previous MDC is restored afterwards.
 */
public final class TracingInterceptor implements OperationInterceptor {

    private final ObservationRegistry registry;

    public TracingInterceptor(ObservationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Map<String, Object> around(OperationCall call, Proceed next) throws TransportException {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        Map<String, String> mdc = new HashMap<>(previous == null ? Map.of() : previous);
        put(mdc, "caseId", call.caseId());
        put(mdc, "recipe", call.recipeRef());
        put(mdc, "recipeVersion", String.valueOf(call.recipeVersion()));
        put(mdc, "stepId", call.stepId());
        put(mdc, "opRef", call.opRef());
        put(mdc, "lender", call.lender());
        put(mdc, "loanRequestId", call.audit() == null ? null : call.audit().loanRequestId());
        MDC.setContextMap(mdc);

        Observation observation = Observation.createNotStarted("lih.l1.operation.call", registry)
                .contextualName(String.valueOf(call.opRef()))
                .lowCardinalityKeyValue("op", String.valueOf(call.opRef()))
                .lowCardinalityKeyValue("kind", String.valueOf(call.def().kind()))
                .highCardinalityKeyValue("caseId", String.valueOf(call.caseId()))
                .start();
        try (Observation.Scope ignored = observation.openScope()) {
            return next.proceed();
        } catch (TransportException | RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }

    private static void put(Map<String, String> mdc, String key, String value) {
        if (value != null) {
            mdc.put(key, value);
        }
    }
}
