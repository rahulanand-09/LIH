package com.rupeek.flow.observability;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * Static facade over Micrometer's global registry, so executors/engine/audit code (several of which are built by
 * hand with {@code new} in tests and dev tools) can record metrics without a constructor change. The Spring
 * {@code MeterRegistry} is attached to the global registry by {@link ObservabilityAutoConfiguration}; with none
 * attached every call is a cheap no-op. Tag cardinality is bounded by design: only config-defined names
 * (flowRef, nodeId, taskRef, resource) and small enums ever become tags, never a loanRequestId or workflowId.
 */
public final class FlowMetrics {

    public static final String NODE_OUTCOMES = "lih.flow.node.outcomes";
    public static final String PARK_EVENTS = "lih.flow.park.events";
    public static final String TASK_EXECUTIONS = "lih.task.executions";
    public static final String TASK_DURATION = "lih.task.duration";
    public static final String TASK_RETRIES = "lih.task.retries";
    public static final String HTTP_CALLS = "lih.http.calls";
    public static final String HTTP_LATENCY = "lih.http.latency";
    public static final String AUDIT_DROPPED = "lih.audit.dropped";
    public static final String REGISTRY_PUBLISH = "lih.registry.publish";
    public static final String REGISTRY_CACHE = "lih.registry.cache";

    private FlowMetrics() {
    }

    public static void count(String name, String... tags) {
        Metrics.counter(name, tags).increment();
    }

    public static void time(String name, Duration d, String... tags) {
        Timer.builder(name).tags(tags).publishPercentileHistogram().register(Metrics.globalRegistry).record(d);
    }

    public static String statusClass(int status) {
        return (status / 100) + "xx";
    }
}
