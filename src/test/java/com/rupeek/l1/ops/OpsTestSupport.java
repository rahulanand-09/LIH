package com.rupeek.l1.ops;

import com.rupeek.l1.expr.CelExpressionEngine;
import com.rupeek.l1.expr.ExprLimits;
import com.rupeek.l1.expr.TemplateEvaluator;
import com.rupeek.l1.expr.fn.RkFunctions;
import com.rupeek.l1.model.ConfigJson;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.Resource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Shared helpers for operation tests: config from JSON text, one template evaluator. */
public final class OpsTestSupport {

    public static final TemplateEvaluator TEMPLATES =
            new TemplateEvaluator(new CelExpressionEngine(ExprLimits.DEFAULT, List.of(new RkFunctions())));

    private OpsTestSupport() {
    }

    public static OperationDef op(String json) {
        return ConfigJson.operation(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "test-op.json")
                .withVersion(1);
    }

    public static Resource resource(String json) {
        return ConfigJson.resource(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "test-resource.json");
    }
}
