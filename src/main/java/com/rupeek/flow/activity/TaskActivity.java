package com.rupeek.flow.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The only activity a flow's leaf TASK nodes call. One generic contract for every {@link
 * com.rupeek.flow.model.OperationKind} — the operation's own retry policy is set by the workflow caller at each
 * call site (kind-specific {@code ActivityOptions}), not by which Java interface was chosen. Failures leave as a
 * Temporal {@code ApplicationFailure} whose type is a {@link TaskFailureType}.
 */
@ActivityInterface
public interface TaskActivity {

    @ActivityMethod(name = "ExecuteTask")
    TaskResult execute(TaskRequest request);
}
