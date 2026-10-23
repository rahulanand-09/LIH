package com.rupeek.flow.resource;

import com.rupeek.flow.task.FlowTaskFailure;
import com.rupeek.flow.task.TaskFailureType;

import java.util.function.Function;

/**
 * POC secrets: environment variables, falling back to JVM system properties. Flow-native port of
 * {@code com.rupeek.l1.secrets.EnvSecretResolver}.
 */
public final class EnvSecretResolver implements SecretResolver {

    private final Function<String, String> lookup;

    public EnvSecretResolver() {
        this(name -> {
            String value = System.getenv(name);
            return value != null ? value : System.getProperty(name);
        });
    }

    /** For tests: supply the lookup. */
    public EnvSecretResolver(Function<String, String> lookup) {
        this.lookup = lookup;
    }

    @Override
    public String resolve(String secretRef) {
        String value = secretRef == null ? null : lookup.apply(secretRef);
        if (value == null || value.isEmpty()) {
            throw new FlowTaskFailure(TaskFailureType.PERMANENT, "secret '" + secretRef + "' is not configured");
        }
        return value;
    }
}
