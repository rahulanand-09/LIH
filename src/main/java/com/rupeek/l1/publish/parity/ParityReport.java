package com.rupeek.l1.publish.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.rupeek.l1.publish.parity.JsonDiff.Diff;
import com.rupeek.l1.publish.parity.ParityRunner.CaseResult;
import com.rupeek.l1.publish.parity.ParityRunner.Status;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Text report of a parity run. Values are masked by default (last four characters kept): the legacy payloads hold
 * real customer data.
 */
public final class ParityReport {

    private ParityReport() {
    }

    public static String text(List<CaseResult> results, boolean showValues) {
        StringBuilder b = new StringBuilder();
        Map<String, Integer> byField = new TreeMap<>();
        for (CaseResult r : results) {
            b.append(String.format("%-6s %s%n", r.status(), r.id()));
            if (r.message() != null) {
                b.append("       ").append(r.message()).append('\n');
            }
            for (Diff d : r.diffs()) {
                b.append("       ").append(d.path()).append(": legacy=").append(show(d.legacy(), showValues))
                        .append(" ours=").append(show(d.ours(), showValues)).append('\n');
                byField.merge(d.path().replaceAll("\\[\\d+]", "[*]"), 1, Integer::sum);
            }
        }
        long match = results.stream().filter(r -> r.status() == Status.MATCH).count();
        long diff = results.stream().filter(r -> r.status() == Status.DIFF).count();
        long error = results.stream().filter(r -> r.status() == Status.ERROR).count();
        b.append(String.format("%ncases: %d  match: %d  diff: %d  error: %d%n", results.size(), match, diff, error));
        if (!byField.isEmpty()) {
            b.append("fields that differ (cases):\n");
            byField.forEach((f, n) -> b.append(String.format("  %-40s %d%n", f, n)));
        }
        return b.toString();
    }

    static String show(JsonNode v, boolean showValues) {
        if (v == null) {
            return "<absent>";
        }
        if (v.isNull()) {
            return "null";
        }
        if (showValues || v.isBoolean()) {
            return v.toString();
        }
        String s = v.isTextual() ? v.asText() : v.toString();
        String kind = v.isTextual() ? "" : "(" + v.getNodeType().name().toLowerCase() + ")";
        if (s.length() <= 4) {
            return kind + "\"" + "*".repeat(s.length()) + "\"";
        }
        return kind + "\"" + "X".repeat(s.length() - 4) + s.substring(s.length() - 4) + "\"";
    }
}
