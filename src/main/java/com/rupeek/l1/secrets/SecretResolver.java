package com.rupeek.l1.secrets;

/**
 * Turns a {@code secretRef} from config into its value at call time. Values are never logged, recorded or
 * passed into expressions.
 */
public interface SecretResolver {

    /**
     * @throws com.rupeek.l1.ops.OperationException with class PERMANENT if the secret is not configured
     */
    String resolve(String secretRef);
}
