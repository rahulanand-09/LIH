package com.rupeek.flow.observability;

import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.util.Arrays;

/**
 * Replay-safe logging for workflow code. {@link Workflow#getLogger} (verified against temporal-sdk 1.40.0:
 * {@code io.temporal.internal.logging.ReplayAwareLogger}) drops every line while the SDK is replaying history,
 * so a cached-out workflow being rebuilt does not re-emit its old log lines. The SDK has no MDC support and
 * workflow coroutines hop across pooled threads, so MDC is NOT held across workflow code: each call sets the
 * keys, logs synchronously on the same thread, and restores them before returning. Metrics recorded from workflow
 * code must also be guarded with {@link #live()} for the same reason.
 */
public final class WorkflowLog {

    private final Logger logger;

    public WorkflowLog(Logger logger) {
        this.logger = logger;
    }

    /** True when this execution is new (not a history replay). */
    public static boolean live() {
        return !Workflow.isReplaying();
    }

    /** @param mdc alternating key,value pairs added to workflowId/runId (null values skipped) */
    public void info(String[] mdc, String msg, Object... args) {
        if (live()) {
            try (LogContext ignored = context(mdc)) {
                logger.info(msg, args);
            }
        }
    }

    public void debug(String[] mdc, String msg, Object... args) {
        if (live() && logger.isDebugEnabled()) {
            try (LogContext ignored = context(mdc)) {
                logger.debug(msg, args);
            }
        }
    }

    public void warn(String[] mdc, String msg, Object... args) {
        if (live()) {
            try (LogContext ignored = context(mdc)) {
                logger.warn(msg, args);
            }
        }
    }

    public void error(String[] mdc, String msg, Object... args) {
        if (live()) {
            try (LogContext ignored = context(mdc)) {
                logger.error(msg, args);
            }
        }
    }

    private static LogContext context(String[] mdc) {
        var info = Workflow.getInfo();
        String[] all = Arrays.copyOf(mdc, mdc.length + 4);
        all[mdc.length] = LogContext.WORKFLOW_ID;
        all[mdc.length + 1] = info.getWorkflowId();
        all[mdc.length + 2] = LogContext.RUN_ID;
        all[mdc.length + 3] = info.getRunId();
        return LogContext.with(all);
    }
}
