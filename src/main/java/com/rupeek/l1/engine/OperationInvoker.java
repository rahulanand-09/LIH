package com.rupeek.l1.engine;

import com.rupeek.l1.expr.CompiledExpr;
import com.rupeek.l1.expr.ExprEvalException;
import com.rupeek.l1.expr.ExpressionEngine;
import com.rupeek.l1.intercept.InterceptorChain;
import com.rupeek.l1.intercept.OperationCall;
import com.rupeek.l1.model.ConfigJson.ConfigException;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.Resource;
import com.rupeek.l1.ops.CallRecorder;
import com.rupeek.l1.ops.ErrorClass;
import com.rupeek.l1.ops.ExecContext;
import com.rupeek.l1.ops.OperationException;
import com.rupeek.l1.ops.OperationRegistry;
import com.rupeek.l1.ops.TransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs one operation the same way for every kind: resolve its resource, run the executor inside the
 * interceptor chain, check {@code success}, classify failures, then build {@code out} and check {@code required}.
 */
public final class OperationInvoker {

    private static final Logger LOG = LoggerFactory.getLogger(OperationInvoker.class);

    private final OperationRegistry operations;
    private final ResourceProvider resources;
    private final InterceptorChain interceptors;
    private final ExpressionEngine expressions;
    private final ErrorClassifier classifier;

    public OperationInvoker(OperationRegistry operations, ResourceProvider resources, InterceptorChain interceptors,
                            ExpressionEngine expressions, ErrorClassifier classifier) {
        this.operations = operations;
        this.resources = resources;
        this.interceptors = interceptors;
        this.expressions = expressions;
        this.classifier = classifier;
    }

    Map<String, Object> invoke(CompiledRecipe.Step step, Map<String, Object> params, StepContext ctx) {
        CompiledOperation op = step.op();
        OperationDef def = op.def();
        String opRef = op.ref().toString();

        Resource resource;
        String baseUrl;
        Map<String, Object> env;
        try {
            resource = def.resource() == null ? null : resources.resource(def.resource());
            baseUrl = resource != null && resource.kind() == Resource.ResourceKind.HTTP ? resources.baseUrl(resource) : null;
            env = resource == null ? Map.of() : resources.values(resource);
        } catch (ConfigException e) {
            throw failure(ErrorClass.PERMANENT, e.getMessage(), step, opRef, null, e);
        }
        ctx.notePii(def.params(), params);

        ExecContext exec = new ExecContext(resource, baseUrl, env, ctx.sys(), new CallRecorder());
        OperationCall call = new OperationCall(ctx.caseId(), ctx.recipe().ref(), ctx.recipe().version(),
                ctx.recipe().lender(), step.id(), opRef, def, auditInfo(op, params, env, ctx), params, exec);

        Map<String, Object> scope;
        try {
            scope = interceptors.invoke(call, () -> operations.executor(def.kind()).execute(op.prepared(), params, exec));
        } catch (TransportException e) {
            throw failure(classifier.forTransport(def, e), e.getMessage(), step, opRef, null, e);
        } catch (OperationException e) {
            throw failure(e.errorClass(), e.getMessage(), step, opRef, e.httpStatus(), e);
        } catch (ExprEvalException | ConfigException e) {
            throw failure(ErrorClass.PERMANENT, e.getMessage(), step, opRef, null, e);
        }

        Map<String, Object> vars = new HashMap<>(scope);
        vars.put("params", params);
        vars.put("env", env);
        Integer status = httpStatus(scope);

        if (op.success() != null && !evalBool(op.success(), vars, step, opRef)) {
            ErrorClass cls = op.classify() != null
                    ? classifier.parse(eval(op.classify(), vars, step, opRef), op.classify().configPath())
                    : classifier.forFailedSuccess(def, status);
            throw failure(cls, "success check failed" + (status == null ? "" : " (HTTP " + status + ")"),
                    step, opRef, status, null);
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        op.out().forEach((name, expr) -> outputs.put(name, eval(expr, vars, step, opRef)));
        for (String r : op.required()) {
            if (outputs.get(r) == null) {
                throw failure(ErrorClass.PERMANENT, "required output '" + r + "' is missing", step, opRef, status, null);
            }
        }
        return outputs;
    }

    private OperationCall.AuditInfo auditInfo(CompiledOperation op, Map<String, Object> params, Map<String, Object> env,
                                              StepContext ctx) {
        String requestType = op.auditLiteral();
        if (op.auditExpr() != null) {
            try {
                Map<String, Object> vars = new HashMap<>();
                vars.put("params", params);
                vars.put("env", env);
                vars.put("sys", ctx.sys());
                Object v = expressions.eval(op.auditExpr(), vars);
                requestType = v == null ? null : String.valueOf(v);
            } catch (ExprEvalException e) {
                LOG.warn("audit type not resolved for {}: {}", op.ref(), e.getMessage());
                requestType = null;
            }
        }
        return requestType == null ? null
                : new OperationCall.AuditInfo(ctx.auditKey("loanRequestId"), ctx.auditKey("customerId"), requestType);
    }

    private boolean evalBool(CompiledExpr expr, Map<String, Object> vars, CompiledRecipe.Step step, String opRef) {
        try {
            return expressions.evalBool(expr, vars);
        } catch (ExprEvalException e) {
            throw failure(ErrorClass.PERMANENT, e.getMessage(), step, opRef, httpStatus(vars), e);
        }
    }

    private Object eval(CompiledExpr expr, Map<String, Object> vars, CompiledRecipe.Step step, String opRef) {
        try {
            return expressions.eval(expr, vars);
        } catch (ExprEvalException e) {
            throw failure(ErrorClass.PERMANENT, e.getMessage(), step, opRef, httpStatus(vars), e);
        }
    }

    private static Integer httpStatus(Map<String, Object> scope) {
        if (scope.get("resp") instanceof Map<?, ?> resp && resp.get("status") instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    private static L1Failure failure(ErrorClass cls, String message, CompiledRecipe.Step step, String opRef,
                                     Integer status, Throwable cause) {
        return new L1Failure(cls, message, step.id(), opRef, status, cause);
    }
}
