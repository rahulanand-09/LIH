package com.rupeek.l1.dev;

import com.rupeek.l1.api.L1FailureType;
import com.rupeek.l1.api.RegistryActivity;
import com.rupeek.l1.api.TaskActivity;
import com.rupeek.l1.api.TaskRequest;
import com.rupeek.l1.api.TaskResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DEV / DEMO ONLY. The Axis precheck as L2 would run it: one workflow, every lender step one L1 activity on the
 * {@code lih-l1} task queue, routing only on outcome labels.
 *
 * <pre>
 * ResolveRecipes ── pin recipe versions once per case
 * dedupe ──IN_PROGRESS──► end (another attempt owns this loan request)
 *   │ INITIATED / ALREADY_INITIATED / REJECTED   (lendercore calls Timble even after a failed dedupe)
 * timble
 *   │
 * wait for the Timble callback (signal) ──► timbleCallback
 *   │
 * status ──PENDING──► sleep, poll again (until a deadline)
 *   │ SUCCESS ──► notify.verified        FAILURE ──► notify.failed        ALREADY_FINAL ──► end
 * </pre>
 * Logs go through {@link Workflow#getLogger}, so they are written once, not again on replay.
 */
public class ReferenceAxisPrecheckWorkflowImpl implements ReferenceAxisPrecheckWorkflow {

    private static final Logger LOG = Workflow.getLogger(ReferenceAxisPrecheckWorkflowImpl.class);

    static final Duration CALLBACK_WAIT = Duration.ofHours(24);
    static final Duration DEFAULT_POLL_EVERY = Duration.ofMinutes(5);
    static final int MAX_POLLS = 50;
    static final List<String> RECIPES = List.of("axis.precheck.dedupe", "axis.precheck.timble",
            "axis.precheck.timbleCallback", "axis.precheck.status", "precheck.notify.verified", "precheck.notify.failed");

    private final RegistryActivity registry = Workflow.newActivityStub(RegistryActivity.class, ActivityOptions.newBuilder()
            .setTaskQueue("lih-l1")
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .build());

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

    private final List<String> history = new ArrayList<>();
    private Map<String, Object> callback;
    private Map<String, Integer> pinned;
    private String caseId;

    @Override
    public String run(Start start) {
        caseId = Workflow.getInfo().getWorkflowId();
        String lr = start.loanRequestId();
        int runNo = start.runNo();
        Duration pollEvery = start.pollEverySeconds() == null ? DEFAULT_POLL_EVERY
                : Duration.ofSeconds(start.pollEverySeconds());
        LOG.info("[L2 {}] ═══ Axis precheck started: loanRequestId={} attempt={} loanProcess={}",
                caseId, lr, runNo, start.loanProcess());

        pinned = registry.resolveRecipes(RECIPES);
        LOG.info("[L2 {}] pinned recipe versions for this case: {}", caseId, pinned);

        Map<String, Object> attempt = Map.of("loanRequestId", lr, "runNo", runNo, "loanProcess", start.loanProcess());
        String dedupe = task("axis.precheck.dedupe", runNo, attempt).outcome();
        if (dedupe.equals("IN_PROGRESS")) {
            return end("SKIPPED", "another attempt owns this loan request");
        }
        task("axis.precheck.timble", runNo, attempt);

        LOG.info("[L2 {}] ⏸ waiting for the Timble callback (signal 'timbleCallback', up to {})", caseId, CALLBACK_WAIT);
        Workflow.await(CALLBACK_WAIT, () -> callback != null);
        if (callback != null) {
            task("axis.precheck.timbleCallback", runNo, Map.of("event", callback));
        } else {
            LOG.info("[L2 {}] no Timble callback within {}; polling status anyway", caseId, CALLBACK_WAIT);
        }

        for (int poll = 1; poll <= MAX_POLLS; poll++) {
            LOG.info("[L2 {}] status poll {}/{}", caseId, poll, MAX_POLLS);
            String status = task("axis.precheck.status", runNo, Map.of("loanRequestId", lr)).outcome();
            switch (status) {
                case "SUCCESS" -> {
                    task("precheck.notify.verified", runNo, Map.of("loanRequestId", lr));
                    return end("APPROVED", "all checks passed, rupeekweb notified");
                }
                case "FAILURE" -> {
                    task("precheck.notify.failed", runNo, Map.of("loanRequestId", lr, "lender", "axis"));
                    return end("REJECTED", "a check failed, rupeekweb notified");
                }
                case "ALREADY_FINAL", "NO_PRECHECK" -> {
                    return end(status, "nothing to poll");
                }
                default -> {
                    LOG.info("[L2 {}] ⏸ still PENDING, next poll in {}", caseId, pollEvery);
                    Workflow.sleep(pollEvery);
                }
            }
        }
        return end("TIMED_OUT", "status still PENDING after " + MAX_POLLS + " polls");
    }

    private TaskResult task(String recipe, int runNo, Map<String, Object> inputs) {
        int version = pinned.get(recipe);
        LOG.info("[L2 {}] ▶ RunTask {}@{} inputs={}", caseId, recipe, version, inputs.keySet());
        TaskResult r = l1.runTask(new TaskRequest(recipe, version, caseId, runNo, new HashMap<>(inputs)));
        history.add(recipe + "=" + r.outcome());
        LOG.info("[L2 {}] ◀ {} → {} {}", caseId, recipe, r.outcome(), r.outputs().isEmpty() ? "" : r.outputs());
        return r;
    }

    private String end(String result, String why) {
        LOG.info("[L2 {}] ═══ Axis precheck finished: {} ({}); steps: {}", caseId, result, why, history);
        return result;
    }

    @Override
    public void timbleCallback(Map<String, Object> event) {
        LOG.info("[L2 {}] ✉ Timble callback received: name_match={} aadhar_match={}", caseId,
                event.get("name_match"), event.get("aadhar_match"));
        this.callback = event;
    }

    @Override
    public List<String> history() {
        return history;
    }
}
