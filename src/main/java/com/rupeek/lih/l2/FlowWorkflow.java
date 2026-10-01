package com.rupeek.lih.l2;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.Map;

@WorkflowInterface
public interface FlowWorkflow {
    @WorkflowMethod
    Map<String, Object> run(FlowRequest req);

    /** Synchronous event: callback or status pull. Runs the flow's named event steps, returns its `returns` vars. */
    @UpdateMethod
    Map<String, Object> event(String name, Map<String, Object> payload);

    @QueryMethod
    Map<String, Object> state();
}
