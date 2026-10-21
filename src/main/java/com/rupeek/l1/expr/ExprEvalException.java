package com.rupeek.l1.expr;

/**
 * An expression failed at runtime (missing key, wrong type, function error). The engine treats this as a
 * {@code PERMANENT} failure: retrying the same config on the same data gives the same result.
 */
public class ExprEvalException extends RuntimeException {

    private final String configPath;
    private final String source;

    public ExprEvalException(String configPath, String source, String reason, Throwable cause) {
        super(configPath + ": '" + source + "' failed: " + reason, cause);
        this.configPath = configPath;
        this.source = source;
    }

    public String configPath() {
        return configPath;
    }

    public String source() {
        return source;
    }
}
