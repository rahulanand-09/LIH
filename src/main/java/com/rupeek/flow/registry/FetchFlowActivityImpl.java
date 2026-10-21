package com.rupeek.flow.registry;

import com.rupeek.flow.model.FlowDef;
import com.rupeek.l1.model.ConfigJson.ConfigException;
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
        } catch (ConfigException e) {
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), "ConfigError");
        }
    }
}
