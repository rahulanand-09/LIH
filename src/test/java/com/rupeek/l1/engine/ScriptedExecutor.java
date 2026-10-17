package com.rupeek.l1.engine;

import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.OperationKind;
import com.rupeek.l1.ops.ExecContext;
import com.rupeek.l1.ops.OperationExecutor;
import com.rupeek.l1.ops.PreparedOperation;
import com.rupeek.l1.ops.TransportException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A fake HTTP executor: each operation answers from a script; every call is recorded. */
final class ScriptedExecutor implements OperationExecutor {

    @FunctionalInterface
    interface Answer {
        Map<String, Object> answer(Map<String, Object> params) throws TransportException;
    }

    record Call(String op, Map<String, Object> params) {
    }

    private final Map<String, Answer> script = new HashMap<>();
    final List<Call> calls = new ArrayList<>();

    ScriptedExecutor on(String op, Answer answer) {
        script.put(op, answer);
        return this;
    }

    static Answer respond(int status, Object body) {
        return params -> {
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", status);
            resp.put("headers", Map.of());
            resp.put("body", body);
            return Map.of("resp", resp);
        };
    }

    @Override
    public OperationKind kind() {
        return OperationKind.HTTP;
    }

    @Override
    public PreparedOperation prepare(OperationDef def) {
        return () -> def;
    }

    @Override
    public Map<String, Object> execute(PreparedOperation op, Map<String, Object> params, ExecContext ctx)
            throws TransportException {
        calls.add(new Call(op.def().op(), params));
        Answer a = script.get(op.def().op());
        if (a == null) {
            throw new IllegalStateException("no scripted answer for " + op.def().op());
        }
        return a.answer(params);
    }
}
