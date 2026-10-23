package com.rupeek.flow.task.executor.gen;

import com.rupeek.flow.task.FlowTaskFailure;
import com.rupeek.flow.task.TaskFailureType;
import com.rupeek.flow.task.TaskRequest;
import com.rupeek.flow.task.TaskResult;
import com.rupeek.flow.expr.ScopeDecl;
import com.rupeek.flow.expr.TemplateEvaluator;
import com.rupeek.flow.expr.fn.RkFunctions;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GEN} nodes: values that must be stable across retries. Flow-native port of
 * {@code com.rupeek.l1.ops.gen.GenOperationExecutor}, collapsed to a single pass (no separate prepare/compile
 * step — see the plan's rationale for why native executors interpret {@code fields} fresh on every call).
 *
 * <p>{@code fields}: {@code action} ({@code STABLE_ID} or {@code NOW}), {@code params} (a template map — values
 * may be CEL, e.g. {@code "seed": "=inputs.seed"} — only {@code reuse}/{@code seed} are read, for
 * {@code STABLE_ID}). Always returns {@code {"value": <result>}} as the node's outputs, default outcome
 * {@code OK} (handled uniformly by {@link com.rupeek.flow.task.TaskActivityImpl}, same convention as MAP).
 */
public final class GenTaskExecutor {

    private static final List<String> ACTIONS = List.of("STABLE_ID", "NOW");

    private final TemplateEvaluator templates;

    public GenTaskExecutor(TemplateEvaluator templates) {
        this.templates = templates;
    }

    /**
     * Checks {@code action} and compiles {@code params} (the one CEL-bearing field a GEN node has) without
     * rendering it. Called both from {@link #execute} and a publish-time check (see
     * {@code com.rupeek.flow.registry.TaskPublisher}).
     */
    public void validate(Map<String, Object> fields) {
        Object actionValue = fields.get("action");
        if (!(actionValue instanceof String action) || !ACTIONS.contains(action)) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT,
                    "validate.fields.action: must be one of " + ACTIONS + ", got " + actionValue);
        }
        if (action.equals("STABLE_ID")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> paramsTemplate = (Map<String, Object>) fields.getOrDefault("params", Map.of());
            templates.compile(paramsTemplate, ScopeDecl.OPERATION, "validate.fields.params");
        }
    }

    public TaskResult execute(TaskRequest request) {
        Map<String, Object> fields = request.fields();
        validate(fields);
        String action = (String) fields.get("action");

        Object value;
        if (action.equals("NOW")) {
            value = Instant.now().toString();
        } else {
            Map<String, Object> vars = new HashMap<>();
            vars.put("inputs", request.inputs());
            vars.put("env", Map.of());
            vars.put("sys", sys(request));
            @SuppressWarnings("unchecked")
            Map<String, Object> paramsTemplate = (Map<String, Object>) fields.getOrDefault("params", Map.of());
            Object rendered = templates.render(
                    templates.compile(paramsTemplate, ScopeDecl.OPERATION, request.nodeId() + ".fields.params"), vars);
            Map<?, ?> params = rendered instanceof Map<?, ?> m ? m : Map.of();
            Object reuse = params.get("reuse");
            if (reuse instanceof String s && !s.isEmpty()) {
                value = s;
            } else if (params.get("seed") instanceof String seed && !seed.isEmpty()) {
                value = RkFunctions.uuidFrom(seed);
            } else {
                throw new FlowTaskFailure(TaskFailureType.PERMANENT,
                        request.nodeId() + ": GEN STABLE_ID needs a non-empty 'seed' (or 'reuse')");
            }
        }
        return new TaskResult("OK", Map.of("value", value));
    }

    private static Map<String, Object> sys(TaskRequest request) {
        Map<String, Object> sys = new HashMap<>();
        sys.put("now", Instant.now());
        sys.put("caseId", request.caseId());
        sys.put("runNo", request.runNo());
        return sys;
    }
}
