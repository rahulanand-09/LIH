package com.rupeek.l1.reference;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;
import java.util.Map;

/**
 * TEST ONLY: a minimal L2 for the Axis precheck, written to show (and test) exactly how L2 drives L1. It is not
 * the L2 implementation; it is the contract in executable form. See docs/l1-for-l2.md.
 */
@WorkflowInterface
public interface ReferenceAxisPrecheckWorkflow {

    /** @param runNo the attempt; a re-precheck of the same loan request is a new workflow run with runNo + 1 */
    @WorkflowMethod
    String run(String loanRequestId, String loanProcess, int runNo);

    /** The Timble callback, delivered by L2's HTTP endpoint after L1 resolved which case it belongs to. */
    @SignalMethod
    void timbleCallback(Map<String, Object> event);

    /** Outcomes of every L1 task so far, in order (recipe=outcome). */
    @QueryMethod
    List<String> history();
}
