package com.rupeek.flow.expr;

import dev.cel.runtime.CelRuntime;

/**
 * A compiled, ready-to-evaluate expression. Immutable and thread-safe; cached with its config version.
 */
public final class CompiledExpr {

    private final String source;
    private final String configPath;
    private final ScopeDecl scope;
    private final CelRuntime.Program program;

    CompiledExpr(String source, String configPath, ScopeDecl scope, CelRuntime.Program program) {
        this.source = source;
        this.configPath = configPath;
        this.scope = scope;
        this.program = program;
    }

    public String source() {
        return source;
    }

    public String configPath() {
        return configPath;
    }

    public ScopeDecl scope() {
        return scope;
    }

    CelRuntime.Program program() {
        return program;
    }

    @Override
    public String toString() {
        return configPath + ": " + source;
    }
}
