package com.rupeek.l1.intercept;

import com.rupeek.l1.ops.OperationException;
import com.rupeek.l1.ops.TransportException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Map;

/**
 * Times every operation call: {@code lih.l1.operation} (Prometheus: {@code lih_l1_operation_seconds}) tagged
 * {@code recipe}, {@code step}, {@code op}, {@code kind}, {@code result}. {@code result} is {@code ok},
 * {@code transport}, or the error class name in lower case.
 */
public final class MetricsInterceptor implements OperationInterceptor {

    private final MeterRegistry registry;

    public MetricsInterceptor(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Map<String, Object> around(OperationCall call, Proceed next) throws TransportException {
        Timer.Sample sample = Timer.start(registry);
        String result = "ok";
        try {
            return next.proceed();
        } catch (TransportException e) {
            result = "transport";
            throw e;
        } catch (OperationException e) {
            result = e.errorClass().name().toLowerCase();
            throw e;
        } catch (RuntimeException e) {
            result = "error";
            throw e;
        } finally {
            try {
                sample.stop(Timer.builder("lih.l1.operation")
                        .tag("recipe", String.valueOf(call.recipeRef()))
                        .tag("step", String.valueOf(call.stepId()))
                        .tag("op", String.valueOf(call.opRef()))
                        .tag("kind", String.valueOf(call.def().kind()))
                        .tag("result", result)
                        .register(registry));
            } catch (RuntimeException ignored) {
                // metrics must never fail a call
            }
        }
    }
}
