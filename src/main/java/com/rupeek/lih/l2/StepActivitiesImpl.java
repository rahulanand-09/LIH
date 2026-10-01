package com.rupeek.lih.l2;

import io.temporal.failure.ApplicationFailure;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class StepActivitiesImpl implements StepActivities {
    public interface Sink { void accept(Map<String, Object> vars) throws Exception; }

    private final Map<String, OperationDef> ops;                 // key "lender/op"
    private final Map<String, ProtocolAdapter> adapters;
    private final Map<String, Function<Map<String, Object>, Map<String, Object>>> transformers;
    private final Map<String, Sink> sinks;

    public StepActivitiesImpl(Map<String, OperationDef> ops, Map<String, ProtocolAdapter> adapters,
                              Map<String, Function<Map<String, Object>, Map<String, Object>>> transformers,
                              Map<String, Sink> sinks) {
        this.ops = ops; this.adapters = adapters; this.transformers = transformers; this.sinks = sinks;
    }

    @Override
    public Map<String, Object> call(String lenderSlug, String op, Map<String, Object> vars) {
        OperationDef d = ops.get(lenderSlug + "/" + op);
        if (d == null) throw fatal("No L1 operation " + lenderSlug + "/" + op);
        ProtocolAdapter a = adapters.get(d.protocol);
        if (a == null) throw fatal("No adapter for protocol " + d.protocol);
        Map<String, Object> req = new HashMap<>();
        if (d.transformer != null) {
            Function<Map<String, Object>, Map<String, Object>> t = transformers.get(d.transformer);
            if (t == null) throw fatal("No transformer " + d.transformer);
            req = t.apply(vars);
        }
        Map<String, Object> resp;
        try {
            resp = a.execute(d, req);
        } catch (UnsupportedOperationException e) {
            throw fatal(e.getMessage());
        } catch (Exception e) {
            throw new RuntimeException("Transport failure calling " + lenderSlug + "/" + op, e); // retryable
        }
        if (d.extract == null) return resp;
        Map<String, Object> out = new HashMap<>();
        d.extract.forEach((k, expr) -> out.put(k, Expr.eval(expr, resp)));
        return out;
    }

    @Override
    public void sink(String target, Map<String, Object> vars) {
        Sink s = sinks.get(target);
        if (s == null) throw fatal("No sink " + target);
        try {
            s.accept(vars);
        } catch (Exception e) {
            throw new RuntimeException("Sink " + target + " failed", e);
        }
    }

    private static ApplicationFailure fatal(String msg) {
        return ApplicationFailure.newNonRetryableFailure(msg, "ConfigError");
    }
}
