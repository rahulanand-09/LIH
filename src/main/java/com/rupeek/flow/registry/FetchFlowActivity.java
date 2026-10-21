package com.rupeek.flow.registry;

import com.rupeek.flow.model.FlowDef;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Fetches one flow's content by ref+version, for a {@code SubflowNode} whose flowRef isn't already in the
 * caller's flat fixture registry — i.e. the real, published path, as opposed to every toy/unit test's hand-built
 * {@code Map<String, FlowDef>}. An Activity, not a direct call from workflow code: a live registry/store
 * connection isn't deterministic/replayable data, so it can't be workflow state, only something fetched through
 * here and recorded as this activity's result like every other registry call in this engine.
 */
@ActivityInterface
public interface FetchFlowActivity {

    @ActivityMethod(name = "FetchFlow")
    FlowDef fetch(String ref, int version);
}
