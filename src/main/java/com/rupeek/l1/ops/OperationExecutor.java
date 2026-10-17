package com.rupeek.l1.ops;

import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.OperationKind;

import java.util.Map;

/**
 * One generic executor per operation kind. Executors only do I/O: success checks, classification and
 * output extraction happen in the engine, the same way for every kind.
 */
public interface OperationExecutor {

    OperationKind kind();

    /**
     * Validates the kind-specific config and compiles its templates. Called at load and publish time.
     *
     * @throws com.rupeek.l1.model.ConfigJson.ConfigException  if the definition is invalid
     * @throws com.rupeek.l1.expr.ExprCompileException          if a template expression does not compile
     */
    PreparedOperation prepare(OperationDef def);

    /**
     * Runs the operation.
     *
     * @param params operation parameters, plain data, already evaluated by the engine
     * @return the scope for {@code success} / {@code classify} / {@code out}: {@code {resp: ...}} for HTTP,
     * {@code {result: ...}} for the other kinds
     * @throws TransportException  if the call did not complete (the engine classifies it)
     * @throws OperationException  if the call failed with a known class
     */
    Map<String, Object> execute(PreparedOperation op, Map<String, Object> params, ExecContext ctx)
            throws TransportException;
}
