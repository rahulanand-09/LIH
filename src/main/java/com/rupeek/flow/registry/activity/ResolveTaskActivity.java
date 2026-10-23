package com.rupeek.flow.registry.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;
import java.util.Map;

/**
 * Version pinning for {@code taskRef}s, called once near the top of every (sub)flow's own {@code run()} —
 * mirrors {@link ResolveFlowActivity} exactly, just a different {@code DocType}/collection underneath. Workflow
 * code must not read a config store directly (not deterministic); this activity call is what's recorded in that
 * workflow's own history, so replay sees the same versions pinned.
 */
@ActivityInterface
public interface ResolveTaskActivity {

    /**
     * @param refs every taskRef this one flow's own node list references directly (not transitively)
     * @return ref to the version to pin for this execution; fails {@code PERMANENT} if a ref was never published
     */
    @ActivityMethod(name = "ResolveTask")
    Map<String, Integer> resolveVersions(List<String> refs);
}
