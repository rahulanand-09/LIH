package com.rupeek.flow.registry;

import com.rupeek.flow.model.FlowDef;

/** The two things anything resolving a flow version needs. */
public interface FlowSource {

    FlowDef flow(String ref, int version);

    int latestVersion(String ref);
}
