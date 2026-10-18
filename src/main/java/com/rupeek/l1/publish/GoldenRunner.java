package com.rupeek.l1.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rupeek.l1.api.TaskRequest;
import com.rupeek.l1.api.TaskResult;
import com.rupeek.l1.engine.CompiledRecipe;
import com.rupeek.l1.engine.ErrorClassifier;
import com.rupeek.l1.engine.L1Failure;
import com.rupeek.l1.engine.OperationInvoker;
import com.rupeek.l1.engine.OutputGuard;
import com.rupeek.l1.engine.RecipeRunner;
import com.rupeek.l1.engine.RecipeSource;
import com.rupeek.l1.engine.ResourceProvider;
import com.rupeek.l1.expr.ExpressionEngine;
import com.rupeek.l1.intercept.InterceptorChain;
import com.rupeek.l1.intercept.PiiMasker;
import com.rupeek.l1.model.OperationDef;
import com.rupeek.l1.model.OperationKind;
import com.rupeek.l1.ops.ExecContext;
import com.rupeek.l1.ops.OperationExecutor;
import com.rupeek.l1.ops.OperationRegistry;
import com.rupeek.l1.ops.PreparedOperation;
import com.rupeek.l1.ops.TransportException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs golden cases through the real engine ({@link RecipeRunner}, invoker, classification, output guard); only
 * the executors are swapped. HTTP operations must be mocked. MAP and GEN run for real. MONGO operations without a
 * mock go to an in-memory recorder: reads find nothing, writes report one matched document.
 */
public final class GoldenRunner {

    /** Default {@code sys.now} so ids and timestamps are reproducible. */
    public static final Instant DEFAULT_NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final Comparator<JsonNode> NUMERIC = (a, b) -> {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue());
        }
        return a.equals(b) ? 0 : 1;
    };
    private static final Pattern SEGMENT = Pattern.compile("([^.\\[\\]]+)|\\[(\\d+)]");

    /** Result of one case; {@code problems} is empty when it passed. */
    public record GoldenResult(String name, String source, List<String> problems) {
        public boolean passed() {
            return problems.isEmpty();
        }
    }

    record Call(String op, Map<String, Object> params) {
    }

    private final ExpressionEngine expressions;
    private final OperationRegistry realOperations;

    public GoldenRunner(ExpressionEngine expressions, OperationRegistry realOperations) {
        this.expressions = expressions;
        this.realOperations = realOperations;
    }

    public GoldenResult run(String source, GoldenCase c, CompiledRecipe recipe, ResourceProvider resources) {
        Recording rec = new Recording(c.mocks());
        List<OperationExecutor> executors = new ArrayList<>();
        for (OperationKind kind : OperationKind.values()) {
            executors.add(new GoldenExecutor(realOperations.executor(kind), rec));
        }
        OperationRegistry operations = new OperationRegistry(executors);
        Instant now = c.now() == null ? DEFAULT_NOW : Instant.parse(c.now());
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        RecipeSource single = new RecipeSource() {
            @Override
            public CompiledRecipe recipe(String ref, int version) {
                return recipe;
            }

            @Override
            public int latestVersion(String ref) {
                return recipe.version();
            }
        };
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        OperationInvoker invoker = new OperationInvoker(operations, resources, new InterceptorChain(List.of()),
                expressions, new ErrorClassifier());
        RecipeRunner runner = new RecipeRunner(single, resources, invoker, expressions, new OutputGuard(),
                new PiiMasker(), meters, clock);

        int runNo = c.runNo() == null ? 1 : c.runNo();
        TaskRequest request = new TaskRequest(recipe.ref(), recipe.version(), "golden:" + c.name(), runNo, c.input());
        List<String> problems = new ArrayList<>();
        TaskResult result = null;
        String failure = null;
        try {
            result = runner.run(request, RecipeRunner.Heartbeat.NONE);
        } catch (L1Failure f) {
            failure = f.errorClass().failureType().name();
            if (c.expect().failure() == null) {
                problems.add("failed with " + failure + ": " + f.getMessage());
            }
        } catch (MissingMock m) {
            // reported from rec.missing below
        } catch (RuntimeException e) {
            problems.add("internal error: " + e);
        }
        if (!rec.missing.isEmpty()) {
            rec.missing.forEach(op -> problems.add("no mock for HTTP operation " + op));
        }
        check(c.expect(), result, failure, rec.calls, problems);
        return new GoldenResult(c.name(), source, List.copyOf(problems));
    }

    private static void check(GoldenCase.Expect expect, TaskResult result, String failure, List<Call> calls,
                              List<String> problems) {
        if (expect.failure() != null) {
            if (result != null) {
                problems.add("expected failure " + expect.failure() + " but got outcome " + result.outcome());
            } else if (failure != null && !failure.equals(expect.failure())) {
                problems.add("expected failure " + expect.failure() + " but failed with " + failure);
            }
        }
        if (expect.outcome() != null && result != null && !expect.outcome().equals(result.outcome())) {
            problems.add("expected outcome " + expect.outcome() + " but got " + result.outcome());
        }
        if (result != null) {
            expect.out().forEach((k, v) -> {
                if (!result.outputs().containsKey(k)) {
                    problems.add("out." + k + ": missing");
                } else if (!same(result.outputs().get(k), v)) {
                    problems.add("out." + k + ": expected " + v + " but got " + result.outputs().get(k));
                }
            });
        }
        expect.calls().forEach((op, paths) -> {
            Call first = calls.stream().filter(x -> x.op().equals(op)).findFirst().orElse(null);
            if (first == null) {
                problems.add("expected a call to " + op);
                return;
            }
            Map<String, Object> root = new HashMap<>();
            root.put("params", first.params());
            paths.forEach((path, v) -> {
                Object actual;
                try {
                    actual = at(root, path);
                } catch (IllegalArgumentException e) {
                    problems.add(op + " " + path + ": " + e.getMessage());
                    return;
                }
                if (!same(actual, v)) {
                    problems.add(op + " " + path + ": expected " + v + " but got " + actual);
                }
            });
        });
        for (String op : expect.notCalled()) {
            if (calls.stream().anyMatch(x -> x.op().equals(op))) {
                problems.add("expected no call to " + op);
            }
        }
    }

    private static boolean same(Object actual, Object expected) {
        return JSON.valueToTree(actual).equals(NUMERIC, JSON.valueToTree(expected));
    }

    /** Resolves {@code a.b[0].c}; missing keys resolve to null, a wrong shape is an error. */
    static Object at(Object root, String path) {
        Object cur = root;
        Matcher m = SEGMENT.matcher(path);
        while (m.find()) {
            if (cur == null) {
                return null;
            }
            if (m.group(1) != null) {
                if (!(cur instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException("'" + m.group(1) + "' is not inside an object");
                }
                cur = map.get(m.group(1));
            } else {
                if (!(cur instanceof List<?> list)) {
                    throw new IllegalArgumentException("[" + m.group(2) + "] is not inside a list");
                }
                int i = Integer.parseInt(m.group(2));
                cur = i < list.size() ? list.get(i) : null;
            }
        }
        return cur;
    }

    /** A mocked HTTP operation that has no mock; ends the run. */
    static final class MissingMock extends RuntimeException {
        MissingMock(String op) {
            super("no mock for HTTP operation " + op);
        }
    }

    static final class Recording {
        final Map<String, Map<String, Object>> mocks;
        final List<Call> calls = new ArrayList<>();
        final List<String> missing = new ArrayList<>();

        Recording(Map<String, Map<String, Object>> mocks) {
            this.mocks = mocks;
        }
    }

    /** Wraps the real executor of one kind: validation is real, execution is mocked or recorded. */
    private static final class GoldenExecutor implements OperationExecutor {
        private final OperationExecutor real;
        private final Recording rec;

        GoldenExecutor(OperationExecutor real, Recording rec) {
            this.real = real;
            this.rec = rec;
        }

        @Override
        public OperationKind kind() {
            return real.kind();
        }

        @Override
        public PreparedOperation prepare(OperationDef def) {
            return real.prepare(def);
        }

        @Override
        public Map<String, Object> execute(PreparedOperation op, Map<String, Object> params, ExecContext ctx)
                throws TransportException {
            OperationDef def = op.def();
            String ref = def.op() + "@" + def.version();
            rec.calls.add(new Call(ref, params));
            Map<String, Object> mock = rec.mocks.get(ref);
            if (mock != null) {
                return answer(mock, ctx);
            }
            return switch (def.kind()) {
                case MAP, GEN -> real.execute(op, params, ctx);
                case MONGO -> recorded(def);
                case HTTP -> {
                    rec.missing.add(ref);
                    throw new MissingMock(ref);
                }
            };
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> answer(Map<String, Object> mock, ExecContext ctx) throws TransportException {
            Map<String, Object> out = new HashMap<>();
            if (mock.get("transport") instanceof Map<?, ?> t) {
                boolean sent = Boolean.TRUE.equals(t.get("sent"));
                boolean timeout = Boolean.TRUE.equals(t.get("timeout"));
                throw new TransportException(sent, timeout, "golden: transport failure (sent=" + sent + ")", null);
            }
            if (mock.containsKey("resp")) {
                Map<String, Object> resp = new HashMap<>((Map<String, Object>) mock.get("resp"));
                resp.putIfAbsent("headers", Map.of());
                resp.putIfAbsent("body", null);
                ctx.recorder().response(resp.get("status") instanceof Number n ? n.intValue() : null, resp.get("body"));
                out.put("resp", resp);
            } else {
                out.put("result", mock.get("result"));
            }
            return out;
        }

        private static Map<String, Object> recorded(OperationDef def) {
            Object result = switch (def.action()) {
                case "FIND_ONE" -> null;
                case "FIND_MANY" -> List.of();
                case "INSERT" -> mapOf("inserted", 1, "insertedId", null);
                default -> mapOf("matched", 1, "modified", 1, "upsertedId", null);
            };
            Map<String, Object> out = new HashMap<>();
            out.put("result", result);
            return out;
        }

        private static Map<String, Object> mapOf(Object... kv) {
            Map<String, Object> m = new HashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                m.put((String) kv[i], kv[i + 1]);
            }
            return m;
        }
    }
}
