package com.rupeek.flow.workflow;

import com.rupeek.flow.activity.TaskActivity;
import com.rupeek.flow.activity.TaskRequest;
import com.rupeek.flow.activity.TaskResult;
import com.rupeek.flow.model.Edge;
import com.rupeek.flow.model.EndNode;
import com.rupeek.flow.model.FlowDef;
import com.rupeek.flow.model.JoinNode;
import com.rupeek.flow.model.Node;
import com.rupeek.flow.model.NodeResult;
import com.rupeek.flow.model.OnError;
import com.rupeek.flow.model.ParallelNode;
import com.rupeek.flow.model.SubflowNode;
import com.rupeek.flow.model.TaskNode;
import com.rupeek.flow.model.OperationKind;
import com.rupeek.flow.model.WaitNode;
import com.rupeek.flow.registry.FetchFlowActivity;
import com.rupeek.flow.registry.ResolveFlowActivity;
import com.rupeek.l1.api.L1FailureType;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The single generic interpreter: walks a graph of nodes, no shared mutable state — each node's result is
 * written once, keyed by its own id, and later nodes read only from completed ancestors.
 *
 * <p>PARK is a suspend, not a thrown failure: a node that exhausts its activity/child-workflow retries catches
 * that failure itself, marks itself {@code PARKED} (query-visible), and blocks on {@link Workflow#await} for an
 * operator's {@link #resume} signal — sent straight to this workflow's own id, found by traversal, with no
 * correlation registry. Letting PARK propagate as a real exception would fail a sibling {@code JOIN} branch
 * (M4) and make a child-workflow-level retry — which discards the child's progress — the resume mechanism,
 * neither of which is what "parked, waiting on a human" means.
 */
public class FlowWorkflowImpl implements FlowWorkflow {

    /** SUBFLOW nesting cap — a backstop against a cycle the publish-time check missed, not just a correctness net. */
    private static final int MAX_DEPTH = 25;

    private final Map<String, String> nodeStatus = new HashMap<>();
    private final Map<String, ResumeAction> pendingResume = new HashMap<>();
    private final Map<String, Map<String, Object>> pendingEvents = new HashMap<>();
    private final Map<String, Integer> pinnedVersions = new HashMap<>();

    @Override
    public NodeResult run(FlowRunRequest req) {
        return runFlowBody(req.flow(), req, "");
    }

    /**
     * The interpreter loop, reused for both this workflow's own root flow ({@code keyPrefix=""}) and for an
     * inlined macro ({@code keyPrefix="<callSiteId>."}, chained for nested macros) — same workflow execution, no
     * child workflow, so a macro's internal {@code results} stay local to this call (a fresh map per call, same
     * as a real child gets) while {@code nodeStatus}/{@code pendingResume} are prefixed so a macro's internal
     * node ids can never collide with the outer flow's or a sibling macro's, in the one shared instance-level
     * map both scopes read/write for {@link #query} and {@link #resume}.
     */
    private NodeResult runFlowBody(FlowDef flow, FlowRunRequest req, String keyPrefix) {
        Map<String, Node> byId = new HashMap<>();
        flow.nodes().forEach(n -> byId.put(n.id(), n));
        Map<String, NodeResult> results = new HashMap<>();

        // Resolved once, here, at this flow's own start — not transitively for the whole tree up front. A
        // nested subflow (or macro) started later (e.g. after a long WAIT) resolves its own refs at its own
        // start and may legitimately pin a newer version than its caller did.
        pinnedVersions.putAll(resolveVersions(flow));

        String currentId = flow.start();
        while (true) {
            Node node = byId.get(currentId);
            if (node == null) {
                throw ApplicationFailure.newNonRetryableFailure("Unknown node " + currentId, "ConfigError");
            }
            if (node instanceof EndNode end) {
                return new NodeResult(end.status(), InputResolver.resolve(end.outputs(), req.inputs(), results));
            }
            if (node instanceof ParallelNode parallel) {
                // Fire-and-forget: each branch is its own coroutine, writing straight into `results` when it
                // settles. A branch that parks just sits suspended inside its own withPark() — never a failed
                // Promise — so it can't cancel siblings or blow up the JOIN that waits on it.
                for (String branchId : parallel.branches()) {
                    Node branchNode = byId.get(branchId);
                    Async.procedure(() -> {
                        NodeResult branchResult = execute(branchNode, req, results, keyPrefix);
                        results.put(branchId, branchResult);
                        nodeStatus.put(keyPrefix + branchId, "DONE");
                    });
                }
                currentId = nextUnconditional(flow, node.id());
                continue;
            }
            if (node instanceof JoinNode join) {
                Workflow.await(() -> join.from().stream().allMatch(results::containsKey));
                currentId = nextUnconditional(flow, node.id());
                continue;
            }
            NodeResult result = execute(node, req, results, keyPrefix);
            results.put(node.id(), result);
            nodeStatus.put(keyPrefix + node.id(), "DONE");
            currentId = nextNodeId(flow, node.id(), result.outcome());
        }
    }

    @Override
    public void resume(String nodeId, ResumeAction action) {
        pendingResume.put(nodeId, action);
    }

    @Override
    public void event(String name, Map<String, Object> payload) {
        // Buffered regardless of where the graph walk currently is — a callback can arrive before its WAIT
        // node is even reached.
        pendingEvents.put(name, payload);
    }

    @Override
    public FlowState query() {
        return new FlowState(new HashMap<>(nodeStatus));
    }

    private NodeResult execute(Node node, FlowRunRequest req, Map<String, NodeResult> results, String keyPrefix) {
        return switch (node) {
            case TaskNode task -> runTask(task, req, results, keyPrefix);
            case SubflowNode subflow -> runSubflow(subflow, req, results, keyPrefix);
            case WaitNode wait -> runWait(wait);
            case ParallelNode ignored -> throw new IllegalStateException("PARALLEL is handled in run(), not execute()");
            case JoinNode ignored -> throw new IllegalStateException("JOIN is handled in run(), not execute()");
            case EndNode ignored -> throw new IllegalStateException("END is handled in run(), not execute()");
        };
    }

    private NodeResult runTask(TaskNode task, FlowRunRequest req, Map<String, NodeResult> results, String keyPrefix) {
        if (task.kind() == OperationKind.RECIPE) {
            return runRecipeTask(task, req, results, keyPrefix);
        }
        return withPark(keyPrefix + task.id(), () -> {
            Map<String, Object> inputs = InputResolver.resolve(task.in(), req.inputs(), results);
            TaskActivity activity = Workflow.newActivityStub(TaskActivity.class, activityOptions(task.onError()));
            int version = task.taskRef() != null ? pinnedVersions.getOrDefault(task.taskRef(), 1) : 1;
            // runNo is a placeholder until a case/attempt concept exists above this engine; not this milestone's concern.
            TaskRequest request = new TaskRequest(task.kind(), task.taskRef(), version,
                    Workflow.getInfo().getWorkflowId(), task.id(), 1, inputs);
            TaskResult result = activity.execute(request);
            return new NodeResult(result.outcome(), result.outputs());
        });
    }

    /**
     * Delegates the whole node to a published L1 recipe — {@code com.rupeek.l1.api.TaskActivity}, on its own
     * task queue ({@code lih-l1}), so L2 and L1 can run as separate processes even though this test runs both
     * on one worker. {@code runNo} travels as a plain input ({@code inputs.runNo}), same as every other input —
     * the engine itself stays ignorant of what it means.
     */
    private NodeResult runRecipeTask(TaskNode task, FlowRunRequest req, Map<String, NodeResult> results, String keyPrefix) {
        return withPark(keyPrefix + task.id(), () -> {
            Map<String, Object> inputs = InputResolver.resolve(task.in(), req.inputs(), results);
            com.rupeek.l1.api.TaskActivity l1 = Workflow.newActivityStub(com.rupeek.l1.api.TaskActivity.class,
                    ActivityOptions.newBuilder()
                            .setTaskQueue("lih-l1")
                            .setStartToCloseTimeout(Duration.ofMinutes(5))
                            .setRetryOptions(RetryOptions.newBuilder()
                                    .setInitialInterval(Duration.ofSeconds(1))
                                    .setBackoffCoefficient(2.0)
                                    .setMaximumAttempts(task.onError() != null ? Math.max(1, task.onError().retries()) : 1)
                                    .setDoNotRetry(L1FailureType.L1_BUSINESS.name(), L1FailureType.L1_UNKNOWN.name(),
                                            L1FailureType.L1_PERMANENT.name())
                                    .build())
                            .build());
            int version = pinnedVersions.getOrDefault(task.taskRef(), 1);
            int runNo = inputs.get("runNo") instanceof Number n ? n.intValue() : 1;
            com.rupeek.l1.api.TaskResult r = l1.runTask(new com.rupeek.l1.api.TaskRequest(
                    task.taskRef(), version, Workflow.getInfo().getWorkflowId(), runNo, inputs));
            return new NodeResult(r.outcome(), r.outputs());
        });
    }

    /**
     * {@code inline=false} (default): a real Temporal child workflow, as always. {@code inline=true} ("macro"):
     * the same {@code FlowDef} shape, but spliced into THIS workflow execution via a plain recursive call to
     * {@link #runFlowBody} instead — no child workflow id, no separate history. A parked node inside an inline
     * macro resumes via {@code "<keyPrefix><subflow.id()>.<internalNodeId>"} straight on this workflow's own id;
     * there is nothing to traverse to, unlike a real child.
     */
    private NodeResult runSubflow(SubflowNode subflow, FlowRunRequest req, Map<String, NodeResult> results, String keyPrefix) {
        // The flat registry is the fixture path every toy/unit test still uses unchanged. A ref it doesn't have
        // a fixed answer for falls back to a real FetchFlowActivity call, BY THE PINNED VERSION resolveVersions
        // already computed for this flow — the one place that number previously went nowhere: nothing read it
        // before this. An Activity, not inline data: a live store connection can't ride along as workflow state.
        FlowDef childFlow = req.registry().get(subflow.flowRef());
        if (childFlow == null) {
            // subflow.version() is authoritative when present -- it was never put in pinnedVersions at all
            // (resolveVersions skips a pinned ref entirely), so falling back to pinnedVersions here would just
            // silently re-float a reference the author deliberately locked.
            int version = subflow.version() != null
                    ? subflow.version()
                    : pinnedVersions.getOrDefault(subflow.flowRef(), 1);
            FetchFlowActivity fetch = Workflow.newActivityStub(FetchFlowActivity.class,
                    ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());
            childFlow = fetch.fetch(subflow.flowRef(), version);
        }
        if (childFlow == null) {
            throw ApplicationFailure.newNonRetryableFailure("Unknown flow " + subflow.flowRef(), "ConfigError");
        }
        int childDepth = req.depth() + 1;
        if (childDepth > MAX_DEPTH) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Subflow depth exceeded " + MAX_DEPTH + " at " + childFlow.flowRef(), "ConfigError");
        }
        if (req.flowRefChain().contains(childFlow.flowRef())) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Cycle detected: " + childFlow.flowRef() + " already in " + req.flowRefChain(), "ConfigError");
        }
        List<String> childChain = new ArrayList<>(req.flowRefChain());
        childChain.add(childFlow.flowRef());

        Map<String, Object> childInputs = InputResolver.resolve(subflow.in(), req.inputs(), results);
        FlowRunRequest childReq = new FlowRunRequest(childFlow, childInputs, req.registry(), childDepth, childChain);

        if (subflow.inline()) {
            return runFlowBody(childFlow, childReq, keyPrefix + subflow.id() + ".");
        }

        return withPark(keyPrefix + subflow.id(), () -> {
            // ABANDON, not the default TERMINATE: a parked grandchild must survive this parent completing/retrying.
            // An explicit execution timeout, not an implicit default — a child that can itself park indefinitely
            // (withPark) must not be bounded by whatever a platform default happens to be.
            ChildWorkflowOptions options = ChildWorkflowOptions.newBuilder()
                    .setWorkflowId(Workflow.getInfo().getWorkflowId() + ":" + subflow.id())
                    .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
                    .setWorkflowExecutionTimeout(Duration.ofDays(1))
                    .build();
            FlowWorkflow child = Workflow.newChildWorkflowStub(FlowWorkflow.class, options);
            return child.run(childReq);
        });
    }

    /**
     * Collects this flow's own direct taskRef/flowRef and resolves them to pinned versions, once, at its start —
     * recipe refs through L1's own real {@code RegistryActivity}, everything else through this engine's own
     * placeholder (unused by any Axis-precheck-shaped flow today, kept for a future non-L1 operation kind).
     */
    private Map<String, Integer> resolveVersions(FlowDef flow) {
        List<String> recipeRefs = flow.nodes().stream()
                .flatMap(n -> n instanceof TaskNode t && t.kind() == OperationKind.RECIPE
                        ? Stream.of(t.taskRef()) : Stream.<String>empty())
                .distinct()
                .toList();
        // A SubflowNode with its own pinned version() needs no resolution at all -- it already knows its number,
        // forever, regardless of whatever the registry currently calls latest (§9's "v1 caller, v2 caller, side
        // by side" case: only an UNPINNED ref ever asks "what's latest").
        List<String> otherRefs = flow.nodes().stream()
                .flatMap(n -> switch (n) {
                    case TaskNode t when t.kind() != OperationKind.RECIPE && t.taskRef() != null -> Stream.of(t.taskRef());
                    case SubflowNode s when s.version() == null -> Stream.of(s.flowRef());
                    default -> Stream.<String>empty();
                })
                .distinct()
                .toList();

        Map<String, Integer> resolved = new HashMap<>();
        if (!recipeRefs.isEmpty()) {
            com.rupeek.l1.api.RegistryActivity registry = Workflow.newActivityStub(
                    com.rupeek.l1.api.RegistryActivity.class, ActivityOptions.newBuilder()
                            .setTaskQueue("lih-l1").setStartToCloseTimeout(Duration.ofSeconds(30)).build());
            resolved.putAll(registry.resolveRecipes(recipeRefs));
        }
        if (!otherRefs.isEmpty()) {
            ResolveFlowActivity registry = Workflow.newActivityStub(ResolveFlowActivity.class,
                    ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());
            resolved.putAll(registry.resolveVersions(otherRefs));
        }
        return resolved;
    }

    /** Parks until {@code wait}'s named event arrives (or its timeout, if one is set) — not a PARK, just a wait. */
    private NodeResult runWait(WaitNode wait) {
        boolean arrived;
        if (wait.timeout() != null) {
            arrived = Workflow.await(wait.timeout(), () -> pendingEvents.containsKey(wait.event()));
        } else {
            Workflow.await(() -> pendingEvents.containsKey(wait.event()));
            arrived = true;
        }
        if (arrived) {
            return new NodeResult("RECEIVED", pendingEvents.remove(wait.event()));
        }
        return new NodeResult(wait.onTimeoutOutcome(), Map.of());
    }

    /**
     * Runs {@code attempt}; if it exhausts its own retries (activity) or fails unexpectedly (child workflow),
     * parks {@code nodeId} instead of propagating, and waits for an operator's {@link #resume}.
     */
    private NodeResult withPark(String nodeId, Supplier<NodeResult> attempt) {
        while (true) {
            try {
                return attempt.get();
            } catch (ActivityFailure | ChildWorkflowFailure e) {
                nodeStatus.put(nodeId, "PARKED");
                Workflow.await(() -> pendingResume.containsKey(nodeId));
                ResumeAction action = pendingResume.remove(nodeId);
                if ("SKIP".equals(action.action())) {
                    return new NodeResult(action.outcome(), Map.of());
                }
                // RETRY: loop back and re-attempt with a fresh budget.
            }
        }
    }

    private ActivityOptions activityOptions(OnError onError) {
        int attempts = onError != null ? Math.max(1, onError.retries()) : 1;
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(attempts).build())
                .build();
    }

    private String nextNodeId(FlowDef flow, String fromId, String outcome) {
        return flow.edges().stream()
                .filter(e -> e.from().equals(fromId) && outcome.equals(e.on()))
                .map(Edge::to)
                .findFirst()
                .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                        "No edge from " + fromId + " on outcome " + outcome, "ConfigError"));
    }

    /** The edge leaving a PARALLEL or JOIN node — unconditional (no {@code on}), never label-based. */
    private String nextUnconditional(FlowDef flow, String fromId) {
        return flow.edges().stream()
                .filter(e -> e.from().equals(fromId) && e.on() == null)
                .map(Edge::to)
                .findFirst()
                .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                        "No unconditional edge from " + fromId, "ConfigError"));
    }
}
