package com.rupeek.flow.task.executor.map;

import com.rupeek.flow.task.FlowTaskFailure;
import com.rupeek.flow.task.TaskFailureType;
import com.rupeek.flow.task.TaskRequest;
import com.rupeek.flow.task.TaskResult;
import com.rupeek.flow.expr.ScopeDecl;
import com.rupeek.flow.expr.TemplateEvaluator;
import com.rupeek.flow.resource.ResourceProvider;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code MAP} nodes: render a template from inputs. No I/O. Flow-native port of
 * {@code com.rupeek.l1.ops.map.MapOperationExecutor}, with two additions that didn't exist in L1:
 * <ul>
 *   <li>(plan decision #2) the rendered template's root map may contain a reserved {@code outcome} key — if
 *       present, it is popped out and used as {@link TaskResult#outcome}; the rest becomes
 *       {@link TaskResult#outputs}. This is how a pure CEL guard (an L1 recipe step with only {@code when}/
 *       {@code return}, no operation) becomes a native node: a MAP node whose template is just
 *       {@code {"outcome": "=<condition> ? 'A' : 'B'"}}. Without an {@code outcome} key, the default outcome is
 *       {@code OK}, matching every data-shaping MAP step L1 had.</li>
 *   <li>an optional {@code fields.resource} — some MAP templates need a resource's non-secret {@code env}
 *       values (e.g. {@code map.axis.precheckRequest}'s {@code env.preCheckAgreementId}), not just I/O
 *       credentials; when present, {@code env} is populated the same way HTTP/MONGO nodes resolve it.</li>
 * </ul>
 */
public final class MapTaskExecutor {

    private final TemplateEvaluator templates;
    private final ResourceProvider resources;

    public MapTaskExecutor(TemplateEvaluator templates, ResourceProvider resources) {
        this.templates = templates;
        this.resources = resources;
    }

    /**
     * Compiles the {@code template} (the one CEL-bearing field a MAP node has) without rendering it — no
     * {@code inputs}/{@code env} data needed, compiling only checks the CEL against {@link ScopeDecl#OPERATION}'s
     * declared variables. Called both from {@link #execute} and a publish-time check (see
     * {@code com.rupeek.flow.registry.TaskPublisher}).
     */
    public void validate(Map<String, Object> fields) {
        Object templateDef = fields.get("template");
        if (templateDef == null) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, "validate.fields.template: is required");
        }
        templates.compile(templateDef, ScopeDecl.OPERATION, "validate.fields.template");
    }

    public TaskResult execute(TaskRequest request) {
        validate(request.fields());
        Object templateDef = request.fields().get("template");
        Map<String, Object> env = request.fields().get("resource") instanceof String resourceRef
                ? resources.values(resources.resource(resourceRef)) : Map.of();
        Map<String, Object> vars = new HashMap<>();
        vars.put("inputs", request.inputs());
        vars.put("env", env);
        vars.put("sys", sys(request));

        Object rendered = templates.render(
                templates.compile(templateDef, ScopeDecl.OPERATION, request.nodeId() + ".fields.template"), vars);
        if (!(rendered instanceof Map<?, ?> renderedMap)) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, request.nodeId() + ": MAP template must render to an object");
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        renderedMap.forEach((k, v) -> outputs.put(String.valueOf(k), v));
        Object outcome = outputs.remove("outcome");
        if (outcome != null && !(outcome instanceof String)) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, request.nodeId() + ": MAP 'outcome' must render to a string");
        }
        return new TaskResult(outcome == null ? "OK" : (String) outcome, outputs);
    }

    private static Map<String, Object> sys(TaskRequest request) {
        Map<String, Object> sys = new HashMap<>();
        sys.put("now", Instant.now());
        sys.put("caseId", request.caseId());
        sys.put("runNo", request.runNo());
        return sys;
    }
}
