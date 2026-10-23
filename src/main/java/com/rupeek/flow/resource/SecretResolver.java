package com.rupeek.flow.resource;

import com.rupeek.flow.task.FlowTaskFailure;

/**
 * Turns a {@code secretRef} from config into its value at call time. Values are never logged, recorded or
 * passed into expressions. Flow-native port of {@code com.rupeek.l1.secrets.SecretResolver}.
 */
public interface SecretResolver {

    /** @throws FlowTaskFailure (PERMANENT) if the secret is not configured */
    String resolve(String secretRef);
}
