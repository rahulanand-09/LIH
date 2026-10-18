package com.rupeek.l1.dev;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;
import java.util.Map;

/**
 * DEV / DEMO ONLY: a minimal L2 for the Axis precheck, showing how L2 drives L1: one workflow per case, every
 * lender step an L1 activity, routing only on outcome labels. It is not the L2 implementation; it is the contract
 * in executable form (see docs/l1-for-l2.md). Registered only with {@code l1.dev.reference-workflow=true}.
 */
@WorkflowInterface
public interface ReferenceAxisPrecheckWorkflow {

    /**
     * @param loanRequestId   the loan request
     * @param loanProcess     passed through to the precheck audit rows
     * @param runNo           the attempt; a re-precheck of the same loan request is a new run with runNo + 1
     * @param pollEverySeconds status polling interval; null = 5 minutes (shorter for demos)
     */
    record Start(String loanRequestId, String loanProcess, int runNo, Integer pollEverySeconds) {
    }

    @WorkflowMethod
    String run(Start start);

    /** The Timble callback, delivered by L2's HTTP endpoint. */
    @SignalMethod
    void timbleCallback(Map<String, Object> event);

    /** Outcomes of every L1 task so far, in order (recipe=outcome). */
    @QueryMethod
    List<String> history();
}
