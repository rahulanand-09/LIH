package com.rupeek.l1.ops;

import com.rupeek.l1.model.ConfigJson;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.OperationKind;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Looks up the executor for an operation kind. */
public final class OperationRegistry {

    private final Map<OperationKind, OperationExecutor> executors = new EnumMap<>(OperationKind.class);

    public OperationRegistry(List<OperationExecutor> executors) {
        for (OperationExecutor e : executors) {
            if (this.executors.put(e.kind(), e) != null) {
                throw new IllegalStateException("two executors registered for kind " + e.kind());
            }
        }
    }

    public OperationExecutor executor(OperationKind kind) {
        OperationExecutor e = executors.get(kind);
        if (e == null) {
            throw new ConfigJson.ConfigException("no executor registered for operation kind " + kind);
        }
        return e;
    }

    public PreparedOperation prepare(OperationDef def) {
        if (def.kind() == null) {
            throw new ConfigJson.ConfigException(def.op() + ": operation kind is missing");
        }
        return executor(def.kind()).prepare(def);
    }
}
