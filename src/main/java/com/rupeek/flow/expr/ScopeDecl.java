package com.rupeek.flow.expr;

import java.util.ArrayList;
import java.util.List;

/**
 * The variables an expression may use at one place in config. Anything else fails to compile.
 * All variables are declared {@code dyn}: lender data is untyped JSON, and dyn keeps {@code x != null} legal.
 *
 * <p>Flow has one unified input bag ({@code inputs}, from {@code TaskRequest.inputs}) rather than L1's separate
 * recipe-level {@code input} and operation-level {@code params} — there is no {@code steps} scope at all, since
 * a native flow node is one operation, not a chain of steps.
 *
 * @param name      scope name, used in error messages and as part of the compiler cache key
 * @param variables declared variable names
 */
public record ScopeDecl(String name, List<String> variables) {

    /** Operation request templates: HTTP {@code request}, MONGO {@code filter}/{@code set}/etc, MAP {@code template}. */
    public static final ScopeDecl OPERATION = new ScopeDecl("operation", List.of("inputs", "env", "sys"));

    /** HTTP {@code success}, {@code classify} and {@code out}. */
    public static final ScopeDecl HTTP_RESPONSE = new ScopeDecl("httpResponse", List.of("inputs", "resp", "env"));

    /** MONGO, MAP and GEN {@code out}. */
    public static final ScopeDecl RESULT = new ScopeDecl("result", List.of("inputs", "result"));

    public ScopeDecl {
        variables = List.copyOf(variables);
    }

    /** This scope plus extra variables, e.g. the {@code as} and {@code $let} names of a template {@code $each}. */
    public ScopeDecl with(List<String> extra) {
        if (extra.isEmpty()) {
            return this;
        }
        List<String> all = new ArrayList<>(variables);
        for (String v : extra) {
            if (all.contains(v)) {
                throw new IllegalArgumentException("variable '" + v + "' is already defined in scope " + name);
            }
            all.add(v);
        }
        return new ScopeDecl(name + "+" + String.join("+", extra), all);
    }
}
