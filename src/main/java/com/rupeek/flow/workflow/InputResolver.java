package com.rupeek.flow.workflow;

import com.rupeek.flow.model.NodeResult;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves a node's small {@code in} entries: a value starting with {@code $.} is a path against the flow's own
 * input ({@code $.in.<field>}) or an already-completed ancestor node's result ({@code $.<nodeId>.outputs.<field>}
 * or {@code $.<nodeId>.outcome}); anything else is a literal, passed through as-is. Deliberately not an
 * expression language (that's CEL, inside an L1 recipe's own template) — just enough to wire one node's output,
 * or a plain constant, into another's input without a shared mutable context.
 */
final class InputResolver {

    private InputResolver() {
    }

    static Map<String, Object> resolve(Map<String, String> in, Map<String, Object> flowInputs,
                                        Map<String, NodeResult> results) {
        Map<String, Object> resolved = new HashMap<>();
        if (in != null) {
            in.forEach((key, path) -> resolved.put(key, resolvePath(path, flowInputs, results)));
        }
        return resolved;
    }

    private static Object resolvePath(String path, Map<String, Object> flowInputs, Map<String, NodeResult> results) {
        if (!path.startsWith("$.")) {
            return path;
        }
        String[] parts = path.split("\\.");
        if (parts.length < 3 || !"$".equals(parts[0])) {
            throw new IllegalArgumentException("Bad input path: " + path);
        }
        if ("in".equals(parts[1])) {
            return flowInputs.get(parts[2]);
        }
        NodeResult r = results.get(parts[1]);
        if (r == null) {
            throw new IllegalArgumentException("Node not yet resolved: " + parts[1] + " (path " + path + ")");
        }
        if (parts.length == 4 && "outputs".equals(parts[2])) {
            return r.outputs().get(parts[3]);
        }
        if (parts.length == 3 && "outputs".equals(parts[2])) {
            return r.outputs();
        }
        if (parts.length == 3 && "outcome".equals(parts[2])) {
            return r.outcome();
        }
        throw new IllegalArgumentException("Bad input path: " + path);
    }
}
