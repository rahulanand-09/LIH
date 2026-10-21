package com.rupeek.l1.ops.map;

import com.rupeek.l1.expr.ScopeDecl;
import com.rupeek.l1.expr.TemplateEvaluator;
import com.rupeek.l1.expr.TemplateEvaluator.CompiledTemplate;
import com.rupeek.l1.model.ConfigJson.ConfigException;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.OperationKind;
import com.rupeek.l1.ops.ExecContext;
import com.rupeek.l1.ops.OperationExecutor;
import com.rupeek.l1.ops.PreparedOperation;

import java.util.HashMap;
import java.util.Map;

/** {@code MAP} operations: render a template from parameters. No I/O. Returns {@code {result: rendered}}. */
public final class MapOperationExecutor implements OperationExecutor {

    private final TemplateEvaluator templates;

    public MapOperationExecutor(TemplateEvaluator templates) {
        this.templates = templates;
    }

    @Override
    public OperationKind kind() {
        return OperationKind.MAP;
    }

    record PreparedMap(OperationDef def, CompiledTemplate template) implements PreparedOperation {
    }

    @Override
    public PreparedOperation prepare(OperationDef def) {
        if (def.template() == null) {
            throw new ConfigException(def.op() + ": MAP operation needs a 'template'");
        }
        return new PreparedMap(def, templates.compile(def.template(), ScopeDecl.OPERATION, def.op() + "/template"));
    }

    @Override
    public Map<String, Object> execute(PreparedOperation prepared, Map<String, Object> params, ExecContext ctx) {
        PreparedMap op = (PreparedMap) prepared;
        Map<String, Object> vars = new HashMap<>();
        vars.put("params", params);
        vars.put("env", ctx.env());
        vars.put("sys", ctx.sys());
        Object rendered = templates.render(op.template(), vars);
        Map<String, Object> scope = new HashMap<>();
        scope.put("result", rendered);
        return scope;
    }
}
