package com.rupeek.flow.registry;

import com.rupeek.flow.model.TaskDef;

/** The two things anything resolving a task version needs. Mirrors {@link FlowSource}. */
public interface TaskSource {

    TaskDef task(String ref, int version);

    int latestVersion(String ref);
}
