package com.rupeek.l1.ops.gen;

import com.rupeek.l1.expr.fn.RkFunctions;
import com.rupeek.l1.model.ConfigJson.ConfigException;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.OperationKind;
import com.rupeek.l1.ops.ErrorClass;
import com.rupeek.l1.ops.ExecContext;
import com.rupeek.l1.ops.OperationException;
import com.rupeek.l1.ops.OperationExecutor;
import com.rupeek.l1.ops.PreparedOperation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GEN} operations: values that must be stable across retries.
 * <ul>
 *   <li>{@code STABLE_ID}: returns {@code params.reuse} if non-empty, else a name-based UUID v5 of
 *       {@code params.seed}. The same seed always gives the same id.</li>
 *   <li>{@code NOW}: the engine's {@code sys.now}, fixed for the attempt.</li>
 * </ul>
 */
public final class GenOperationExecutor implements OperationExecutor {

    private static final List<String> ACTIONS = List.of("STABLE_ID", "NOW");

    @Override
    public OperationKind kind() {
        return OperationKind.GEN;
    }

    record PreparedGen(OperationDef def, String action) implements PreparedOperation {
    }

    @Override
    public PreparedOperation prepare(OperationDef def) {
        if (!ACTIONS.contains(def.action())) {
            throw new ConfigException(def.op() + ".action: must be one of " + ACTIONS + ", got " + def.action());
        }
        if (def.action().equals("STABLE_ID") && !def.params().containsKey("seed")) {
            throw new ConfigException(def.op() + ": STABLE_ID needs a 'seed' parameter");
        }
        return new PreparedGen(def, def.action());
    }

    @Override
    public Map<String, Object> execute(PreparedOperation prepared, Map<String, Object> params, ExecContext ctx) {
        PreparedGen op = (PreparedGen) prepared;
        Object result;
        if (op.action().equals("NOW")) {
            Object now = ctx.sys().get("now");
            if (now == null) {
                throw new OperationException(ErrorClass.PERMANENT, op.def().op() + ": sys.now is not set");
            }
            result = now.toString();
        } else {
            Object reuse = params.get("reuse");
            if (reuse instanceof String s && !s.isEmpty()) {
                result = s;
            } else if (params.get("seed") instanceof String seed && !seed.isEmpty()) {
                result = RkFunctions.uuidFrom(seed);
            } else {
                throw new OperationException(ErrorClass.PERMANENT, op.def().op() + ": 'seed' must be a non-empty string");
            }
        }
        Map<String, Object> scope = new HashMap<>();
        scope.put("result", result);
        return scope;
    }
}
