package com.rupeek.flow.registry.store;

/**
 * A flow config document could not be found, parsed or serialised. Flow-native counterpart of
 * {@code com.rupeek.l1.model.ConfigJson.ConfigException}.
 */
public class FlowConfigException extends RuntimeException {
    public FlowConfigException(String message, Throwable cause) {
        super(message, cause);
    }

    public FlowConfigException(String message) {
        super(message);
    }
}
