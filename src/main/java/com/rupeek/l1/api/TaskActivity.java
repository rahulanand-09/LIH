package com.rupeek.l1.api;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The only activity L2 calls into L1. Runs one published recipe (one lender step) and returns one outcome.
 * Failures leave as a Temporal {@code ApplicationFailure} whose type is an {@link L1FailureType}.
 */
@ActivityInterface
public interface TaskActivity {

    @ActivityMethod(name = "RunTask")
    TaskResult runTask(TaskRequest request);
}
