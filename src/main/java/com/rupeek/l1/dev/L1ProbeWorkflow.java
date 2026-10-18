package com.rupeek.l1.dev;

import com.rupeek.l1.api.TaskRequest;
import com.rupeek.l1.api.TaskResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Dev-only workflow that calls {@code RunTask} once, so L1 can be exercised on a real Temporal server before L2
 * exists. Registered only with {@code l1.dev.probe-workflow=true}; never part of a production flow.
 */
@WorkflowInterface
public interface L1ProbeWorkflow {

    String TYPE = "L1Probe";

    @WorkflowMethod(name = TYPE)
    TaskResult run(TaskRequest request);
}
