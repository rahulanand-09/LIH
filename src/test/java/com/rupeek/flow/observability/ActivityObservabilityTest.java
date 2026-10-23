package com.rupeek.flow.observability;

import ch.qos.logback.classic.Level;
import com.rupeek.flow.model.OperationKind;
import com.rupeek.flow.task.TaskRequest;
import com.rupeek.flow.task.TaskResult;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.activity.ActivityInfo;
import io.temporal.common.RetryOptions;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor.ActivityOutput;
import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** MDC population/cleanup on activity threads, task metrics, and the retry decision log. */
class ActivityObservabilityTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void attachRegistry() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
    }

    @AfterEach
    void detachRegistry() {
        Metrics.removeRegistry(registry);
    }

    private static ActivityInfo info(int attempt, int maxAttempts) {
        ActivityInfo info = mock(ActivityInfo.class);
        when(info.getWorkflowId()).thenReturn("wf-1");
        when(info.getRunId()).thenReturn("run-1");
        when(info.getActivityType()).thenReturn("Execute");
        when(info.getAttempt()).thenReturn(attempt);
        when(info.getRetryOptions()).thenReturn(RetryOptions.newBuilder().setMaximumAttempts(maxAttempts)
                .setInitialInterval(Duration.ofSeconds(1)).setBackoffCoefficient(2.0)
                .setMaximumInterval(Duration.ofSeconds(100)).build());
        return info;
    }

    private static TaskRequest task() {
        return new TaskRequest(OperationKind.HTTP, "core.fetch", 3, "wf-1", "fetch", 1,
                Map.of("loanRequestId", "LR-42", "pan", "ABCDE1234F"), Map.of("lender", "axis"));
    }

    @Test
    void mdcIsPopulatedDuringTheCallAndRemovedAfterwards() {
        Map<String, String> seen = new HashMap<>();
        ActivityOutput out = LihWorkerInterceptor.observe(info(2, 5), new Object[] {task()}, () -> {
            seen.putAll(MDC.getCopyOfContextMap());
            return new ActivityOutput(new TaskResult("OK", Map.of()));
        });

        assertThat(out.getResult()).isInstanceOf(TaskResult.class);
        assertThat(seen).containsEntry("workflowId", "wf-1").containsEntry("runId", "run-1")
                .containsEntry("activityType", "Execute").containsEntry("attempt", "2")
                .containsEntry("taskRef", "core.fetch").containsEntry("nodeId", "fetch")
                .containsEntry("lender", "axis").containsEntry("loanRequestId", "LR-42");
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void mdcIsRemovedEvenWhenTheTaskThrows() {
        assertThatThrownBy(() -> LihWorkerInterceptor.observe(info(1, 3), new Object[] {task()}, () -> {
            throw ApplicationFailure.newFailure("boom", "TECHNICAL");
        })).isInstanceOf(ApplicationFailure.class);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void nothingLeaksAcrossTasksOnAReusedPooledThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> LihWorkerInterceptor.observe(info(1, 3), new Object[] {task()},
                    () -> new ActivityOutput(new TaskResult("OK", Map.of())))).get();
            // same thread, next activity is a non-task (fetch/resolve style) activity with a different workflow
            ActivityInfo other = info(1, 3);
            when(other.getWorkflowId()).thenReturn("wf-2");
            Map<String, String> seen = pool.submit(() -> {
                Map<String, String> copy = new HashMap<>();
                LihWorkerInterceptor.observe(other, new Object[] {"not-a-task-request"}, () -> {
                    copy.putAll(MDC.getCopyOfContextMap());
                    return new ActivityOutput(null);
                });
                return copy;
            }).get();
            assertThat(seen).containsEntry("workflowId", "wf-2")
                    .doesNotContainKeys("taskRef", "nodeId", "lender", "loanRequestId");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void taskMetricsAreRecordedWithBoundedTags() {
        LihWorkerInterceptor.observe(info(1, 3), new Object[] {task()}, () -> {
            sleep(5);
            return new ActivityOutput(new TaskResult("OK", Map.of()));
        });

        assertThat(registry.get(FlowMetrics.TASK_EXECUTIONS).tags("taskRef", "core.fetch", "kind", "HTTP", "outcome", "OK")
                .counter().count()).isEqualTo(1.0);
        var timer = registry.get(FlowMetrics.TASK_DURATION).tags("taskRef", "core.fetch", "kind", "HTTP").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(5.0);
        // never a per-case tag
        registry.getMeters().forEach(m -> assertThat(m.getId().getTags())
                .noneMatch(t -> t.getKey().equals("loanRequestId") || t.getKey().equals("workflowId")));
    }

    @Test
    void retryableFailureLogsRetryDecisionAndMasksPiiInTheReason() {
        try (LogCapture logs = new LogCapture(Level.INFO)) {
            assertThatThrownBy(() -> LihWorkerInterceptor.observe(info(2, 5), new Object[] {task()}, () -> {
                throw ApplicationFailure.newFailure("dup key for ABCDE1234F", "TECHNICAL");
            })).isInstanceOf(ApplicationFailure.class);

            assertThat(logs.text()).contains("retryScheduled=true", "nextAttempt=3", "delayMs=2000", "failureType=TECHNICAL");
            assertThat(logs.text()).doesNotContain("ABCDE1234F");
        }
        assertThat(registry.get(FlowMetrics.TASK_RETRIES).counter().count()).isEqualTo(1.0);
        assertThat(registry.get(FlowMetrics.TASK_EXECUTIONS).tag("outcome", "FAILED_TECHNICAL").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void lastAttemptAndNonRetryableTypesAnnouncePark() {
        try (LogCapture logs = new LogCapture(Level.INFO)) {
            assertThatThrownBy(() -> LihWorkerInterceptor.observe(info(5, 5), new Object[] {task()}, () -> {
                throw ApplicationFailure.newFailure("down", "TECHNICAL");
            })).isInstanceOf(ApplicationFailure.class);
            assertThatThrownBy(() -> LihWorkerInterceptor.observe(info(1, 5), new Object[] {task()}, () -> {
                throw ApplicationFailure.newFailure("bad", "PERMANENT");
            })).isInstanceOf(ApplicationFailure.class);

            assertThat(logs.text()).contains("attempts exhausted", "non-retryable failure type", "will PARK");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
