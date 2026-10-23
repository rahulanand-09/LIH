package com.rupeek.flow.observability;

import com.rupeek.flow.audit.PiiMasker;
import com.rupeek.flow.task.AuditFields;
import com.rupeek.flow.task.TaskFailureType;
import com.rupeek.flow.task.TaskRequest;
import com.rupeek.flow.task.TaskResult;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import io.temporal.common.RetryOptions;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.failure.ApplicationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Activity-side observability, registered on the {@code WorkerFactory}. For every activity it puts workflowId,
 * runId, activityType and attempt into the MDC (and, for a {@link TaskRequest}, taskRef/nodeId/lender/loanRequestId)
 * for exactly the duration of the call, then removes them again so the pooled activity thread never leaks them
 * into the next task. For every {@code TaskActivity.execute} it logs start/finish with duration and outcome, and
 * records the task metrics. A failure is logged here, with the retry decision derived from the activity's own
 * {@link RetryOptions} (the workflow cannot see retries: Temporal schedules them server-side).
 */
public final class LihWorkerInterceptor extends WorkerInterceptorBase {

    private static final Logger LOG = LoggerFactory.getLogger("com.rupeek.flow.task");
    private static final PiiMasker MASKER = new PiiMasker();
    private static final int MAX_MESSAGE = 300;

    @Override
    public ActivityInboundCallsInterceptor interceptActivity(ActivityInboundCallsInterceptor next) {
        return new ActivityInboundCallsInterceptorBase(next) {
            private ActivityExecutionContext ctx;

            @Override
            public void init(ActivityExecutionContext context) {
                this.ctx = context;
                super.init(context);
            }

            @Override
            public ActivityOutput execute(ActivityInput input) {
                return observe(ctx.getInfo(), input.getArguments(), () -> super.execute(input));
            }
        };
    }

    interface Call {
        ActivityInboundCallsInterceptor.ActivityOutput run();
    }

    static ActivityInboundCallsInterceptor.ActivityOutput observe(ActivityInfo info, Object[] args, Call call) {
        TaskRequest task = args != null && args.length > 0 && args[0] instanceof TaskRequest t ? t : null;
        try (LogContext ignored = LogContext.with(
                LogContext.WORKFLOW_ID, info.getWorkflowId(),
                LogContext.RUN_ID, info.getRunId(),
                "activityType", info.getActivityType(),
                LogContext.ATTEMPT, String.valueOf(info.getAttempt()))) {
            if (task == null) {
                return call.run();
            }
            try (LogContext taskCtx = LogContext.with(
                    LogContext.TASK_REF, task.taskRef(),
                    LogContext.NODE_ID, task.nodeId(),
                    "kind", String.valueOf(task.kind()),
                    LogContext.LENDER, task.fields() == null ? null : AuditFields.lender(task.fields()),
                    LogContext.LOAN_REQUEST_ID, loanRequestId(task))) {
                return observeTask(info, task, call);
            }
        }
    }

    private static ActivityInboundCallsInterceptor.ActivityOutput observeTask(ActivityInfo info, TaskRequest task, Call call) {
        String ref = task.taskRef() == null ? "inline" : task.taskRef();
        String kind = String.valueOf(task.kind());
        LOG.debug("task start taskRef={} kind={} nodeId={} version={}", ref, kind, task.nodeId(), task.taskVersion());
        long start = System.nanoTime();
        try {
            ActivityInboundCallsInterceptor.ActivityOutput out = call.run();
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            String outcome = out.getResult() instanceof TaskResult r ? r.outcome() : "?";
            LOG.info("task finish taskRef={} kind={} nodeId={} outcome={} durationMs={}", ref, kind, task.nodeId(), outcome,
                    took.toMillis());
            record(ref, kind, outcome, took);
            return out;
        } catch (RuntimeException e) {
            Duration took = Duration.ofNanos(System.nanoTime() - start);
            String type = e instanceof ApplicationFailure af ? af.getType() : e.getClass().getSimpleName();
            logFailure(info, task, ref, kind, type, e, took);
            record(ref, kind, "FAILED_" + type, took);
            throw e;
        }
    }

    private static void logFailure(ActivityInfo info, TaskRequest task, String ref, String kind, String type,
                                   RuntimeException e, Duration took) {
        boolean retryable = retryable(type);
        RetryOptions retry = info.getRetryOptions();
        int max = retry == null ? 1 : retry.getMaximumAttempts();
        boolean more = retryable && (max == 0 || info.getAttempt() < max);
        String msg = maskedMessage(task, e);
        if (more) {
            long delayMs = nextDelayMs(retry, info.getAttempt());
            LOG.warn("task failed taskRef={} kind={} nodeId={} failureType={} durationMs={} retryScheduled=true "
                            + "nextAttempt={} delayMs={} reason={}", ref, kind, task.nodeId(), type, took.toMillis(),
                    info.getAttempt() + 1, delayMs, msg);
            FlowMetrics.count(FlowMetrics.TASK_RETRIES, "taskRef", ref, "kind", kind, "reason", type);
        } else {
            LOG.warn("task failed taskRef={} kind={} nodeId={} failureType={} durationMs={} retryScheduled=false "
                            + "({}) -> flow will PARK this node. reason={}", ref, kind, task.nodeId(), type, took.toMillis(),
                    retryable ? "attempts exhausted" : "non-retryable failure type", msg);
        }
    }

    /** Temporal's own schedule: initial * coefficient^(attempt-1), capped at the maximum interval. */
    static long nextDelayMs(RetryOptions retry, int attempt) {
        if (retry == null || retry.getInitialInterval() == null) {
            return 0;
        }
        double delay = retry.getInitialInterval().toMillis() * Math.pow(retry.getBackoffCoefficient(), attempt - 1);
        if (retry.getMaximumInterval() != null) {
            delay = Math.min(delay, retry.getMaximumInterval().toMillis());
        }
        return (long) delay;
    }

    private static boolean retryable(String type) {
        try {
            return TaskFailureType.valueOf(type).retryable;
        } catch (IllegalArgumentException notOurs) {
            return true; // an unexpected exception type: Temporal retries it
        }
    }

    /** Failure text scrubbed of this call's own PII-keyed input values, then truncated. */
    private static String maskedMessage(TaskRequest task, RuntimeException e) {
        Set<String> sensitive = new HashSet<>();
        PiiMasker.collectPiiValues(task.inputs(), false, sensitive);
        String m = MASKER.maskText(e instanceof ApplicationFailure af ? af.getOriginalMessage() : e.getMessage(), sensitive);
        return m == null || m.length() <= MAX_MESSAGE ? m : m.substring(0, MAX_MESSAGE) + "...";
    }

    private static String loanRequestId(TaskRequest task) {
        Object v = task.inputs() == null ? null : task.inputs().get("loanRequestId");
        return v == null ? null : String.valueOf(v);
    }

    private static void record(String ref, String kind, String outcome, Duration took) {
        FlowMetrics.count(FlowMetrics.TASK_EXECUTIONS, "taskRef", ref, "kind", kind, "outcome", outcome);
        FlowMetrics.time(FlowMetrics.TASK_DURATION, took, "taskRef", ref, "kind", kind);
    }
}
