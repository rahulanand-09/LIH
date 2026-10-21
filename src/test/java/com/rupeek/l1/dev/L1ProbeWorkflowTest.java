package com.rupeek.l1.dev;

import com.rupeek.l1.api.L1FailureType;
import com.rupeek.l1.api.TaskActivity;
import com.rupeek.l1.api.TaskRequest;
import com.rupeek.l1.api.TaskResult;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class L1ProbeWorkflowTest {

    private static final String QUEUE = "lih-l1";
    private static final TaskRequest REQUEST =
            new TaskRequest("axis.precheck.dedupe", 1, "case-1", 1, Map.of("loanRequestId", "LR-1", "runNo", 1));

    private TestWorkflowEnvironment env;
    private Worker worker;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        worker = env.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(L1ProbeWorkflowImpl.class);
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    /** Fails with {@code type} for the first {@code failures} attempts, then succeeds. */
    private AtomicInteger start(L1FailureType type, int failures) {
        AtomicInteger attempts = new AtomicInteger();
        TaskActivity activity = request -> {
            if (attempts.incrementAndGet() <= failures) {
                throw ApplicationFailure.newFailure("scripted", type.name());
            }
            return new TaskResult("INITIATED", Map.of("applicantId", "APP-1"), request.recipeRef(),
                    request.recipeVersion(), List.of());
        };
        worker.registerActivitiesImplementations(activity);
        env.start();
        return attempts;
    }

    private L1ProbeWorkflow probe() {
        return env.getWorkflowClient().newWorkflowStub(L1ProbeWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(QUEUE).build());
    }

    @Test
    void technicalFailuresAreRetried() {
        AtomicInteger attempts = start(L1FailureType.L1_TECHNICAL, 2);
        assertThat(probe().run(REQUEST).outcome()).isEqualTo("INITIATED");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void theOtherFailureTypesAreNeverRetried() {
        // Thrown as retryable on purpose: the probe's doNotRetry list must stop the retry on its own.
        for (L1FailureType type : List.of(L1FailureType.L1_BUSINESS, L1FailureType.L1_UNKNOWN, L1FailureType.L1_PERMANENT)) {
            setUpFresh();
            AtomicInteger attempts = start(type, 1);
            WorkflowFailedException e = catchThrowableOfType(WorkflowFailedException.class, () -> probe().run(REQUEST));
            assertThat(e.getCause()).isInstanceOf(ActivityFailure.class);
            assertThat(((ApplicationFailure) e.getCause().getCause()).getType()).as(type.name()).isEqualTo(type.name());
            assertThat(attempts).as(type.name()).hasValue(1);
        }
    }

    private void setUpFresh() {
        tearDown();
        setUp();
    }
}
