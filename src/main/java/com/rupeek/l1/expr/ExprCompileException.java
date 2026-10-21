package com.rupeek.l1.expr;

/** An expression failed to compile. Raised at load or publish time; never during a case. */
public class ExprCompileException extends RuntimeException {

    private final String configPath;
    private final String source;

    public ExprCompileException(String configPath, String source, String issues) {
        super(configPath + ": cannot compile '" + source + "': " + issues);
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
