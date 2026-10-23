package com.rupeek.flow.expr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles and renders JSON templates (HTTP request parts, MONGO filter/set documents, MAP templates).
 *
 * <ul>
 *   <li>A string starting with {@code =} is a CEL expression; its result replaces the string, keeping its type.</li>
 *   <li>A string starting with {@code ==} is the literal string without the first {@code =}.</li>
 *   <li>Any other value is a literal.</li>
 *   <li>{@code {"$each": "<CEL list>", "as": "x", "$let": {"k": "<CEL>"}, "template": ...}} renders
 *       {@code template} once per item, with {@code x} and each {@code $let} value in scope. {@code $let}
 *       entries are evaluated in order and may use earlier ones. A null list renders as an empty list.</li>
 * </ul>
 * Keys starting with {@code $} are reserved; unknown ones fail at compile time.
 */
public final class TemplateEvaluator {

    private static final Set<String> EACH_KEYS = Set.of("$each", "as", "$let", "template");

    private final ExpressionEngine engine;

    public TemplateEvaluator(ExpressionEngine engine) {
        this.engine = engine;
    }

    public CompiledTemplate compile(Object template, ScopeDecl scope, String configPath) {
        return new CompiledTemplate(compileNode(template, scope, configPath));
    }

    public Object render(CompiledTemplate template, Map<String, Object> vars) {
        return template.root().render(engine, vars);
    }

    private Node compileNode(Object value, ScopeDecl scope, String path) {
        if (value instanceof String s) {
            if (s.startsWith("==")) {
                return new Literal(s.substring(1));
            }
            if (s.startsWith("=")) {
                return new ExprNode(engine.compile(s.substring(1), scope, path));
            }
            return new Literal(s);
        }
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("$each")) {
                return compileEach(map, scope, path);
            }
            Map<String, Node> fields = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (key.startsWith("$")) {
                    throw new ExprCompileException(path + "." + key, key, "unknown template directive");
                }
                fields.put(key, compileNode(e.getValue(), scope, path + "." + key));
            }
            return new MapNode(fields);
        }
        if (value instanceof List<?> list) {
            List<Node> items = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                items.add(compileNode(list.get(i), scope, path + "[" + i + "]"));
            }
            return new ListNode(items);
        }
        return new Literal(value);
    }

    private Node compileEach(Map<?, ?> map, ScopeDecl scope, String path) {
        for (Object key : map.keySet()) {
            if (!EACH_KEYS.contains(String.valueOf(key))) {
                throw new ExprCompileException(path + "." + key, String.valueOf(key),
                        "not allowed in a $each block (allowed: " + EACH_KEYS + ")");
            }
        }
        if (!(map.get("$each") instanceof String listSource) || !(map.get("as") instanceof String as)) {
            throw new ExprCompileException(path, String.valueOf(map.get("$each")),
                    "$each needs a CEL list expression and an 'as' variable name");
        }
        if (!map.containsKey("template")) {
            throw new ExprCompileException(path, listSource, "$each needs a 'template'");
        }
        CompiledExpr list = engine.compile(listSource, scope, path + ".$each");

        ScopeDecl inner = scope.with(List.of(as));
        List<Map.Entry<String, CompiledExpr>> lets = new ArrayList<>();
        if (map.get("$let") instanceof Map<?, ?> letMap) {
            for (Map.Entry<?, ?> e : letMap.entrySet()) {
                String name = String.valueOf(e.getKey());
                lets.add(Map.entry(name, engine.compile(String.valueOf(e.getValue()), inner, path + ".$let." + name)));
                inner = inner.with(List.of(name));
            }
        } else if (map.containsKey("$let")) {
            throw new ExprCompileException(path + ".$let", String.valueOf(map.get("$let")), "$let must be an object");
        }
        Node body = compileNode(map.get("template"), inner, path + ".template");
        return new EachNode(list, as, lets, body, path);
    }

    /** A compiled template, ready to render many times. */
    public record CompiledTemplate(Node root) {
    }

    sealed interface Node permits Literal, ExprNode, MapNode, ListNode, EachNode {
        Object render(ExpressionEngine engine, Map<String, Object> vars);
    }

    record Literal(Object value) implements Node {
        public Object render(ExpressionEngine engine, Map<String, Object> vars) {
            return value;
        }
    }

    record ExprNode(CompiledExpr expr) implements Node {
        public Object render(ExpressionEngine engine, Map<String, Object> vars) {
            return engine.eval(expr, vars);
        }
    }

    record MapNode(Map<String, Node> fields) implements Node {
        public Object render(ExpressionEngine engine, Map<String, Object> vars) {
            Map<String, Object> out = new LinkedHashMap<>();
            fields.forEach((k, node) -> out.put(k, node.render(engine, vars)));
            return out;
        }
    }

    record ListNode(List<Node> items) implements Node {
        public Object render(ExpressionEngine engine, Map<String, Object> vars) {
            List<Object> out = new ArrayList<>(items.size());
            items.forEach(node -> out.add(node.render(engine, vars)));
            return out;
        }
    }

    record EachNode(CompiledExpr list, String as, List<Map.Entry<String, CompiledExpr>> lets, Node body,
                    String path) implements Node {
        public Object render(ExpressionEngine engine, Map<String, Object> vars) {
            Object items = engine.eval(list, vars);
            if (items == null) {
                return new ArrayList<>();
            }
            if (!(items instanceof List<?> values)) {
                throw new ExprEvalException(path + ".$each", list.source(), "expected a list but got "
                        + items.getClass().getSimpleName(), null);
            }
            List<Object> out = new ArrayList<>(values.size());
            for (Object item : values) {
                Map<String, Object> itemVars = new HashMap<>(vars);
                itemVars.put(as, item);
                for (Map.Entry<String, CompiledExpr> let : lets) {
                    itemVars.put(let.getKey(), engine.eval(let.getValue(), itemVars));
                }
                out.add(body.render(engine, itemVars));
            }
            return out;
        }
    }
}
