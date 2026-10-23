package com.rupeek.flow.registry.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;
import java.util.Map;

/**
 * Version pinning, called once near the top of every (sub)flow's own {@code run()}, at every depth — not once
 * for the whole tree up front, since a SUBFLOW only knows its own direct refs, and routing can be runtime
 * dependent. Workflow code must not read a config store directly (not deterministic); this activity call is
 * what's recorded in that workflow's own history, so replay sees the same versions pinned, for that one flow.
 */
@ActivityInterface
public interface ResolveFlowActivity {

    /**
     * @param refs every taskRef/flowRef this one flow's own node list references directly (not transitively)
     * @return ref to the version to pin for this execution; fails {@code PERMANENT} if a ref was never published
     */
    @ActivityMethod(name = "ResolveFlow")
    Map<String, Integer> resolveVersions(List<String> refs);
}
