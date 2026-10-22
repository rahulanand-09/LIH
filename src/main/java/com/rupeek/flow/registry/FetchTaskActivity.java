package com.rupeek.flow.registry;

import com.rupeek.flow.model.TaskDef;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Fetches one task's content by ref+version, for a {@code TaskNode} whose {@code taskRef} is set — the real,
 * published path for node config, as opposed to inlining it into {@code TaskNode.fields} directly. An Activity,
 * not a direct call from workflow code: a live registry/store connection isn't deterministic/replayable data, so
 * it can't be workflow state, only something fetched through here and recorded as this activity's result, like
 * every other registry call in this engine. Mirrors {@link FetchFlowActivity}.
 */
@ActivityInterface
public interface FetchTaskActivity {

    @ActivityMethod(name = "FetchTask")
    TaskDef fetch(String ref, int version);
}
