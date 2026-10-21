package com.rupeek.l1.dev;

import com.rupeek.l1.api.L1FailureType;
import com.rupeek.l1.api.TaskActivity;
import com.rupeek.l1.api.TaskRequest;
import com.rupeek.l1.api.TaskResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * Calls {@code RunTask} with the activity options L2 is expected to use: only {@code L1_TECHNICAL} is retried,
 * the other three failure types end the workflow at once. Timeouts are generous fixed values (Axis allows 120s
 * connect + 120s read); L2 will derive them from the recipe's timeout budget instead.
 */
public class L1ProbeWorkflowImpl implements L1ProbeWorkflow {

    static final ActivityOptions OPTIONS = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(10))
            .setHeartbeatTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofSeconds(30))
                    .setMaximumAttempts(4)
                    .setDoNotRetry(L1FailureType.L1_BUSINESS.name(), L1FailureType.L1_UNKNOWN.name(),
                            L1FailureType.L1_PERMANENT.name())
                    .build())
            .build();

    private final TaskActivity activity = Workflow.newActivityStub(TaskActivity.class, OPTIONS);

    @Override
    public TaskResult run(TaskRequest request) {
        return activity.runTask(request);
    }
}
