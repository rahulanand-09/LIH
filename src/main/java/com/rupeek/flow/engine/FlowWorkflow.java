package com.rupeek.flow.engine;

import com.rupeek.flow.model.NodeResult;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.Map;

/**
 * One interface, reused at every recursion depth and every flow — only the flow data resolved at runtime varies,
 * never the Java type.
 */
@WorkflowInterface
public interface FlowWorkflow {

    @WorkflowMethod
    NodeResult run(FlowRunRequest req);

    /** An operator resolving a parked node — found directly by this workflow's id, via traversal, not a registry. */
    @SignalMethod
    void resume(String nodeId, ResumeAction action);

    /**
     * An external callback. Buffered regardless of where the graph walk currently is — a WAIT node consumes it
     * whenever it's reached, including one that arrived before the walk got there.
     */
    @SignalMethod
    void event(String name, Map<String, Object> payload);

    /** Per-node status snapshot, so ops traversal can find a parked node anywhere in a subflow tree. */
    @QueryMethod
    FlowState query();
}
