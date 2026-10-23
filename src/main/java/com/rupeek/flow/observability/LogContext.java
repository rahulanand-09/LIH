package com.rupeek.flow.observability;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sets MDC keys for the duration of a try-with-resources block and restores whatever was there before on close,
 * so nesting works and a pooled thread never carries a previous task's keys into the next one.
 *
 * <pre>{@code try (LogContext ignored = LogContext.with(LogContext.LOAN_REQUEST_ID, id)) { ... }}</pre>
 *
 * <p>Never use it across a Temporal blocking call in workflow code (workflow threads are pooled and a coroutine
 * can resume on another thread) — see {@link WorkflowLog}, which sets/clears around a single log call instead.
 */
public final class LogContext implements AutoCloseable {

    public static final String LOAN_REQUEST_ID = "loanRequestId";
    public static final String WORKFLOW_ID = "workflowId";
    public static final String RUN_ID = "runId";
    /** {@code flowRef@version} when the version is known. */
    public static final String FLOW_REF = "flowRef";
    public static final String NODE_ID = "nodeId";
    public static final String TASK_REF = "taskRef";
    public static final String LENDER = "lender";
    public static final String ATTEMPT = "attempt";
    /** Set by {@link RequestIdFilter} (and by the api package's request logging) for HTTP requests. */
    public static final String REQUEST_ID = "requestId";

    /** Previous value per key; a null value means the key was absent. */
    private final Map<String, String> previous = new LinkedHashMap<>();

    private LogContext() {
    }

    /** Alternating key, value pairs. A null value is skipped (the key is left as it was). */
    public static LogContext with(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must be key,value pairs");
        }
        LogContext ctx = new LogContext();
        for (int i = 0; i < keyValues.length; i += 2) {
            ctx.put(keyValues[i], keyValues[i + 1]);
        }
        return ctx;
    }

    public LogContext put(String key, String value) {
        if (value != null) {
            previous.putIfAbsent(key, MDC.get(key));
            MDC.put(key, value);
        }
        return this;
    }

    @Override
    public void close() {
        previous.forEach((k, v) -> {
            if (v == null) {
                MDC.remove(k);
            } else {
                MDC.put(k, v);
            }
        });
        previous.clear();
    }

    /** {@code flowRef@version}, or just the ref when the version is unknown. */
    public static String flowRef(String ref, Integer version) {
        return version == null ? ref : ref + "@" + version;
    }
}
