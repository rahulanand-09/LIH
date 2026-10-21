package com.rupeek.l1.publish.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rupeek.l1.engine.CompiledRecipe;
import com.rupeek.l1.engine.ResourceProvider;
import com.rupeek.l1.model.ConfigJson.ConfigException;
import com.rupeek.l1.ops.OperationRegistry;
import com.rupeek.l1.publish.GoldenCase;
import com.rupeek.l1.publish.GoldenRunner;
import com.rupeek.l1.publish.GoldenRunner.Call;
import com.rupeek.l1.publish.GoldenRunner.Run;
import com.rupeek.l1.publish.parity.JsonDiff.Diff;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares the request a recipe would send to a lender with the request the legacy system actually sent.
 *
 * <p>The recipe runs through the real engine. The <b>captured</b> operation (the lender call) is always mocked,
 * so the lender is never called; its request parameter is the "ours" side. Operations listed as <b>live</b>
 * (e.g. the Core customer fetch) run for real; every other HTTP operation needs a mock, and MONGO operations go
 * to the golden recorder (reads find nothing, nothing is written).
 */
public final class ParityRunner {

    public enum Status { MATCH, DIFF, ERROR }

    /** @param id a non-PII id for the report, e.g. the loanRequestId */
    public record CaseResult(String id, Status status, List<Diff> diffs, String message) {
    }

    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();

    private final GoldenRunner runner;
    private final CompiledRecipe recipe;
    private final ResourceProvider resources;
    private final OperationRegistry liveOperations;
    private final Set<String> liveRefs = new LinkedHashSet<>();
    private final String captureRef;
    private final String captureParam;
    private final Set<String> ignore;

    /**
     * @param captureOp    operation name (no version) whose request is compared, e.g. {@code axis.precheck.invoke}
     * @param captureParam its parameter holding the request body, e.g. {@code body}
     * @param liveOps      operation names (no version) allowed to run for real; empty for fully mocked runs
     * @param ignore       {@link JsonDiff} paths that differ by design, e.g. {@code client_loan_id}
     */
    public ParityRunner(GoldenRunner runner, CompiledRecipe recipe, ResourceProvider resources,
                        OperationRegistry liveOperations, Set<String> liveOps, String captureOp,
                        String captureParam, Set<String> ignore) {
        this.runner = runner;
        this.recipe = recipe;
        this.resources = resources;
        this.liveOperations = liveOperations;
        this.captureRef = pinned(captureOp);
        this.captureParam = captureParam;
        this.ignore = Set.copyOf(ignore);
        if (liveOps.contains(captureOp)) {
            throw new ConfigException("the captured operation " + captureOp + " can never run live");
        }
        liveOps.forEach(op -> liveRefs.add(pinned(op)));
    }

    public String captureRef() {
        return captureRef;
    }

    /**
     * @param input  recipe inputs, e.g. {@code {loanRequestId, runNo}}
     * @param mocks  {@code op@version} to golden mock, for operations that are neither live nor captured
     * @param legacy the request body the legacy system sent
     */
    public CaseResult check(String id, Map<String, Object> input, Map<String, Map<String, Object>> mocks,
                            JsonNode legacy) {
        Map<String, Map<String, Object>> all = new HashMap<>(mocks);
        // Any answer will do: the request is captured before the response matters.
        all.put(captureRef, Map.of("resp", Map.of("status", 200, "body", Map.of())));
        GoldenCase c = new GoldenCase("parity:" + id, recipe.ref() + "@" + recipe.version(), input, null, null, all,
                new GoldenCase.Expect("ANY", null, null, null, null));
        Run run = runner.execute(c, recipe, resources, liveOperations, liveRefs);
        Call call = run.calls().stream().filter(x -> x.op().equals(captureRef)).findFirst().orElse(null);
        if (call == null) {
            String why = run.error() != null ? run.error()
                    : !run.missingMocks().isEmpty() ? "no mock for " + run.missingMocks()
                    : run.failure() != null ? run.failure() + ": " + run.failureMessage()
                    : "recipe ended with " + (run.result() == null ? "nothing" : run.result().outcome());
            return new CaseResult(id, Status.ERROR, List.of(), captureRef + " was not reached: " + why);
        }
        JsonNode ours = JSON.valueToTree(call.params().get(captureParam));
        List<Diff> diffs = JsonDiff.diff(legacy, ours, ignore);
        return new CaseResult(id, diffs.isEmpty() ? Status.MATCH : Status.DIFF, diffs, null);
    }

    private String pinned(String opName) {
        return recipe.steps().stream()
                .filter(s -> s.opRef() != null && s.opRef().ref().equals(opName))
                .map(s -> s.opRef().toString())
                .findFirst()
                .orElseThrow(() -> new ConfigException(recipe.ref() + "@" + recipe.version()
                        + " has no step running " + opName));
    }
}
