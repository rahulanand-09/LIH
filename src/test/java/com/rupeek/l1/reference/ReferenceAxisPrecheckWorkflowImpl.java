package com.rupeek.l1.reference;

import com.rupeek.l1.api.L1FailureType;
import com.rupeek.l1.api.RegistryActivity;
import com.rupeek.l1.api.TaskActivity;
import com.rupeek.l1.api.TaskRequest;
import com.rupeek.l1.api.TaskResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Axis precheck as L2 would run it. Every lender step is one {@code RunTask} on the {@code lih-l1} task
 * queue; L2 only routes on outcome labels and never sees customer data.
 *
 * <pre>
 * dedupe ──IN_PROGRESS──► end (another attempt owns this loan request)
 *   │ INITIATED / ALREADY_INITIATED / REJECTED   (lendercore calls Timble even after a failed dedupe)
 * timble
 *   │
 * wait for the Timble callback (signal) ──► timbleCallback
 *   │
 * status ──PENDING──► sleep, poll again (until a deadline)
 *   │ SUCCESS ──► notify.verified        FAILURE ──► notify.failed        ALREADY_FINAL ──► end
 * </pre>
 */
public class ReferenceAxisPrecheckWorkflowImpl implements ReferenceAxisPrecheckWorkflow {

    static final Duration CALLBACK_WAIT = Duration.ofHours(24);
    static final Duration POLL_EVERY = Duration.ofMinutes(5);
    static final int MAX_POLLS = 50;

    /** Only L1_TECHNICAL is retried; the other three types are final for the task. */
    private final TaskActivity l1 = Workflow.newActivityStub(TaskActivity.class, ActivityOptions.newBuilder()
            .setTaskQueue("lih-l1")
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(1))
                    .setBackoffCoefficient(2.0)
                    .setMaximumAttempts(5)
                    .setDoNotRetry(L1FailureType.L1_BUSINESS.name(), L1FailureType.L1_UNKNOWN.name(),
                            L1FailureType.L1_PERMANENT.name())
                    .build())
            .build());

    static final List<String> RECIPES = List.of("axis.precheck.dedupe", "axis.precheck.timble",
            "axis.precheck.timbleCallback", "axis.precheck.status", "precheck.notify.verified", "precheck.notify.failed");

    private final RegistryActivity registry = Workflow.newActivityStub(RegistryActivity.class, ActivityOptions.newBuilder()
            .setTaskQueue("lih-l1")
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .build());

    private final List<String> history = new ArrayList<>();
    private Map<String, Integer> pinned;
    private Map<String, Object> callback;

    @Override
    public String run(String loanRequestId, String loanProcess, int runNo) {
        String caseId = Workflow.getInfo().getWorkflowId();
        pinned = registry.resolveRecipes(RECIPES);   // once per case: later publishes don't change a running case
        Map<String, Object> attempt = Map.of("loanRequestId", loanRequestId, "runNo", runNo, "loanProcess", loanProcess);

        String dedupe = task("axis.precheck.dedupe", caseId, runNo, attempt).outcome();
        if (dedupe.equals("IN_PROGRESS")) {
            return "SKIPPED";
        }
        task("axis.precheck.timble", caseId, runNo, attempt);

        Workflow.await(CALLBACK_WAIT, () -> callback != null);
        if (callback != null) {
            task("axis.precheck.timbleCallback", caseId, runNo, Map.of("event", callback));
        }

        for (int poll = 0; poll < MAX_POLLS; poll++) {
            String status = task("axis.precheck.status", caseId, runNo, Map.of("loanRequestId", loanRequestId)).outcome();
            switch (status) {
                case "SUCCESS" -> {
                    task("precheck.notify.verified", caseId, runNo, Map.of("loanRequestId", loanRequestId));
                    return "APPROVED";
                }
                case "FAILURE" -> {
                    task("precheck.notify.failed", caseId, runNo, Map.of("loanRequestId", loanRequestId, "lender", "axis"));
                    return "REJECTED";
                }
                case "ALREADY_FINAL", "NO_PRECHECK" -> {
                    return status;
                }
                default -> Workflow.sleep(POLL_EVERY);   // PENDING
            }
        }
        return "TIMED_OUT";
    }

    private TaskResult task(String recipe, String caseId, int runNo, Map<String, Object> inputs) {
        TaskResult r = l1.runTask(new TaskRequest(recipe, pinned.get(recipe), caseId, runNo, new HashMap<>(inputs)));
        history.add(recipe + "=" + r.outcome());
        return r;
    }

    @Override
    public void timbleCallback(Map<String, Object> event) {
        this.callback = event;
    }

    @Override
    public List<String> history() {
        return history;
    }
}
