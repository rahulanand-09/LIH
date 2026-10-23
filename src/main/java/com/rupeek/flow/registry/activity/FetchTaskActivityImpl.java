package com.rupeek.flow.registry.activity;

import com.rupeek.flow.registry.TaskSource;

import com.rupeek.flow.model.TaskDef;
import com.rupeek.flow.registry.store.FlowConfigException;
import io.temporal.failure.ApplicationFailure;

/** The real {@link FetchTaskActivity}, backed by the same registry {@link ResolveTaskActivityImpl} resolves versions from. */
public final class FetchTaskActivityImpl implements FetchTaskActivity {

    private final TaskSource tasks;

    public FetchTaskActivityImpl(TaskSource tasks) {
        this.tasks = tasks;
    }

    @Override
    public TaskDef fetch(String ref, int version) {
        try {
            return tasks.task(ref, version);
        } catch (FlowConfigException e) {
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), "ConfigError");
        }
    }
}
