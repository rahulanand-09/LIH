package com.rupeek.flow.registry.activity;

import com.rupeek.flow.registry.FlowSource;

import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.registry.store.FlowConfigException;
import io.temporal.failure.ApplicationFailure;

/** The real {@link FetchFlowActivity}, backed by the same registry {@link ResolveFlowActivityImpl} resolves versions from. */
public final class FetchFlowActivityImpl implements FetchFlowActivity {

    private final FlowSource flows;

    public FetchFlowActivityImpl(FlowSource flows) {
        this.flows = flows;
    }

    @Override
    public FlowDef fetch(String ref, int version) {
        try {
            return flows.flow(ref, version);
        } catch (FlowConfigException e) {
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), "ConfigError");
        }
    }
}
