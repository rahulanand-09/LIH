package com.rupeek.flow.registry;

import com.rupeek.flow.model.FlowDef;

/** Mirrors {@code com.rupeek.l1.engine.RecipeSource} — the two things anything resolving a flow version needs. */
public interface FlowSource {

    FlowDef flow(String ref, int version);

    int latestVersion(String ref);
}
