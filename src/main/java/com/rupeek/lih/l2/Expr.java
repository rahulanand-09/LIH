package com.rupeek.lih.l2;

import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.List;
import java.util.Map;

/**
 * The one evaluator. Read-only SpEL over a map: no types, constructors or beans, so config cannot reach code.
 * Deterministic by construction (no clock, no randomness), safe to run inside a workflow.
 */
public final class Expr {
    private static final SpelExpressionParser PARSER = new SpelExpressionParser();
    private static final Helpers HELPERS = new Helpers();

    private Expr() {}

    public static Object eval(String expr, Map<String, Object> root) {
        EvaluationContext ctx = SimpleEvaluationContext.forPropertyAccessors(new NullSafeMapAccessor())
                .withInstanceMethods().withRootObject(root).build();
        ctx.setVariable("f", HELPERS);
        return PARSER.parseExpression(expr).getValue(ctx);
    }

    public static boolean test(String expr, Map<String, Object> root) {
        return Boolean.TRUE.equals(eval(expr, root));
    }

    /** Missing keys read as null instead of throwing, so `data?.x` and `x == null` both behave. */
    static class NullSafeMapAccessor extends MapAccessor {
        @Override public boolean canRead(EvaluationContext c, Object target, String name) { return target instanceof Map; }
        @Override public TypedValue read(EvaluationContext c, Object target, String name) throws AccessException {
            return new TypedValue(((Map<?, ?>) target).get(name));
        }
    }

    /** Referenced as #f.allEqual(list,'Y') etc. Keep this list short; anything bigger belongs in code. */
    public static class Helpers {
        public boolean allEqual(List<?> l, Object v) { return l.stream().allMatch(x -> eqi(x, v)); }
        public boolean anyEqual(List<?> l, Object v) { return l.stream().anyMatch(x -> eqi(x, v)); }
        public boolean eqi(Object a, Object b) {
            return a != null && b != null && a.toString().trim().equalsIgnoreCase(b.toString().trim());
        }
    }
}
