package com.rupeek.l1.publish;

import com.rupeek.l1.publish.GoldenRunner.GoldenResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outcome of a validate or publish run: what each document would become, every error, every golden result.
 *
 * @param written true only when versions, pointers and resources were actually stored
 */
public record PublishReport(
        String publishId,
        Mode mode,
        String env,
        String by,
        String reason,
        Instant at,
        List<Item> items,
        List<String> errors,
        List<GoldenResult> goldens,
        boolean written) {

    public enum Mode { VALIDATE, DRY_RUN, PUBLISH }

    /** NEW = will be / was inserted; UNCHANGED = same content already stored; UPDATED = resource changed. */
    public enum Action { NEW, UNCHANGED, UPDATED, CHECKED }

    public record Item(String type, String ref, Integer version, Action action, String source) {
    }

    public boolean ok() {
        return errors.isEmpty() && goldens.stream().allMatch(GoldenResult::passed);
    }

    public String toText() {
        StringBuilder b = new StringBuilder();
        b.append(mode).append(" env=").append(env).append(" publishId=").append(publishId).append('\n');
        for (Item i : items) {
            b.append(String.format("  %-9s %-9s %s%s  (%s)%n", i.action(), i.type(), i.ref(),
                    i.version() == null ? "" : "@" + i.version(), i.source()));
        }
        long passed = goldens.stream().filter(GoldenResult::passed).count();
        b.append("golden: ").append(passed).append('/').append(goldens.size()).append(" passed\n");
        for (GoldenResult g : goldens) {
            if (!g.passed()) {
                b.append("  FAIL ").append(g.name()).append("  (").append(g.source()).append(")\n");
                g.problems().forEach(p -> b.append("       - ").append(p).append('\n'));
            }
        }
        if (!errors.isEmpty()) {
            b.append("errors:\n");
            errors.forEach(e -> b.append("  - ").append(e).append('\n'));
        }
        boolean changes = items.stream().anyMatch(i -> i.action() == Action.NEW || i.action() == Action.UPDATED);
        if (!ok()) {
            b.append("FAILED\n");
        } else if (!written) {
            b.append("OK: nothing written\n");
        } else {
            b.append(changes ? "OK: published\n" : "OK: no changes, nothing to publish\n");
        }
        return b.toString();
    }

    /** Plain maps and lists for the publish log. */
    public Map<String, Object> toLog() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_id", publishId);
        m.put("mode", mode.name());
        m.put("env", env);
        m.put("by", by);
        m.put("reason", reason);
        m.put("at", java.util.Date.from(at));
        m.put("ok", ok());
        m.put("written", written);
        List<Map<String, Object>> its = new ArrayList<>();
        for (Item i : items) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("type", i.type());
            x.put("ref", i.ref());
            x.put("version", i.version());
            x.put("action", i.action().name());
            x.put("source", i.source());
            its.add(x);
        }
        m.put("items", its);
        m.put("errors", errors);
        List<Map<String, Object>> gs = new ArrayList<>();
        for (GoldenResult g : goldens) {
            gs.add(Map.of("name", g.name(), "passed", g.passed(), "problems", g.problems()));
        }
        m.put("goldens", gs);
        return m;
    }
}
