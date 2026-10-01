package com.rupeek.lih.l2;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowLock;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The single generic interpreter. Knows step types, not lenders. */
public class FlowWorkflowImpl implements FlowWorkflow {
    private final WorkflowLock lock = Workflow.newWorkflowLock();
    private final Map<String, Object> vars = new HashMap<>();
    private FlowRequest req;
    private boolean ready; // events queue until the main path reaches WAIT

    @Override
    public Map<String, Object> run(FlowRequest r) {
        req = r;
        vars.putAll(r.vars);
        vars.put("done", false);
        runSteps(r.flow.steps);
        Workflow.await(Workflow::isEveryHandlerFinished);
        return new HashMap<>(vars);
    }

    @Override
    public Map<String, Object> event(String name, Map<String, Object> payload) {
        Workflow.await(() -> ready);
        if ("$ready".equals(name)) return new HashMap<>(); // lets a starter block until the flow's initial steps finished
        FlowDef.Event ev = req.flow.events.get(name);
        if (ev == null) throw ApplicationFailure.newNonRetryableFailure("Unknown event " + name, "ConfigError");
        lock.lock(); // one event at a time: they share vars
        try {
            if (!Boolean.TRUE.equals(vars.get("done"))) { // final flows are not re-evaluated (parity: only re-check while PENDING)
                vars.put("event", payload);
                runSteps(ev.steps);
            }
            Map<String, Object> out = new HashMap<>();
            ev.returns.forEach(k -> out.put(k, vars.get(k)));
            return out;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Map<String, Object> state() { return new HashMap<>(vars); }

    private void runSteps(List<FlowDef.Step> steps) {
        for (FlowDef.Step s : steps) {
            if (s.when != null && !Expr.test(s.when, vars)) continue;
            switch (s.type) {
                case "CALL":
                    vars.put(s.as != null ? s.as : s.op, activities(s).call(req.lenderSlug, s.op, new HashMap<>(vars)));
                    break;
                case "DECIDE":
                    vars.put(s.as, Expr.eval(s.expr, vars));
                    break;
                case "PERSIST":
                case "NOTIFY":
                    activities(s).sink(s.target, new HashMap<>(vars));
                    break;
                case "WAIT":
                    ready = true;
                    Workflow.await(() -> Expr.test(s.until, vars));
                    break;
                default:
                    throw ApplicationFailure.newNonRetryableFailure("Unknown step type " + s.type, "ConfigError");
            }
        }
    }

    private StepActivities activities(FlowDef.Step s) {
        return Workflow.newActivityStub(StepActivities.class, ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(s.timeoutSec != null ? s.timeoutSec : 30))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(s.retries != null ? s.retries : 1).build())
                .build());
    }
}
