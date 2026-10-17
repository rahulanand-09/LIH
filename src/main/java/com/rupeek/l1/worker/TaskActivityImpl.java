package com.rupeek.l1.worker;

import com.rupeek.l1.api.FailureDetails;
import com.rupeek.l1.api.L1FailureType;
import com.rupeek.l1.api.TaskActivity;
import com.rupeek.l1.api.TaskRequest;
import com.rupeek.l1.api.TaskResult;
import com.rupeek.l1.engine.L1Failure;
import com.rupeek.l1.engine.RecipeRunner;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code RunTask} activity. Runs the recipe and turns every failure into an {@link ApplicationFailure} whose
 * type is an {@link L1FailureType}. An unexpected exception (a bug) is non-retryable {@code L1_PERMANENT}: a bug
 * must never cause a blind retry of a lender call.
 */
public final class TaskActivityImpl implements TaskActivity {

    private static final Logger LOG = LoggerFactory.getLogger(TaskActivityImpl.class);

    private final RecipeRunner runner;

    public TaskActivityImpl(RecipeRunner runner) {
        this.runner = runner;
    }

    @Override
    public TaskResult runTask(TaskRequest request) {
        try {
            return runner.run(request, stepId -> Activity.getExecutionContext().heartbeat(stepId));
        } catch (L1Failure f) {
            LOG.warn("RunTask {}@{} case {} failed: {}", request.recipeRef(), request.recipeVersion(),
                    request.caseId(), f.getMessage());
            throw f.toApplicationFailure(request.recipeRef(), request.recipeVersion());
        } catch (ApplicationFailure f) {
            throw f;
        } catch (RuntimeException e) {
            LOG.error("RunTask {}@{} case {} hit an internal error", request.recipeRef(), request.recipeVersion(),
                    request.caseId(), e);
            throw ApplicationFailure.newNonRetryableFailure(
                    "internal error: " + e.getClass().getSimpleName(), L1FailureType.L1_PERMANENT.name(),
                    new FailureDetails(request.recipeRef(), request.recipeVersion(), null, null, "PERMANENT", null,
                            "internal error: " + e.getClass().getSimpleName()));
        }
    }
}
