package com.rupeek.l1.secrets;

import com.rupeek.l1.ops.ErrorClass;
import com.rupeek.l1.ops.OperationException;

import java.util.function.Function;

/**
 * POC secrets: environment variables (as lendercore does today), falling back to JVM system properties.
 * Replaced by a vault-backed resolver later without touching config.
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
            throw new OperationException(ErrorClass.PERMANENT, "secret '" + secretRef + "' is not configured");
        }
        return value;
    }
}
