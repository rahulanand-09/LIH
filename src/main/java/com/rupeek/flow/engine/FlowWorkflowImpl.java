package com.rupeek.flow.engine;

import com.rupeek.flow.task.TaskActivity;
import com.rupeek.flow.task.TaskFailureType;
import com.rupeek.flow.task.TaskRequest;
import com.rupeek.flow.task.TaskResult;
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
import com.rupeek.flow.model.WaitNode;
import com.rupeek.flow.model.TaskDef;
import com.rupeek.flow.registry.activity.FetchFlowActivity;
import com.rupeek.flow.registry.activity.FetchTaskActivity;
import com.rupeek.flow.registry.activity.ResolveFlowActivity;
import com.rupeek.flow.registry.activity.ResolveTaskActivity;
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

    /**
     * A {@code taskRef}'d node never carries its own {@code fields} — they're fetched here, by ref+version,
     * exactly how a {@code SubflowNode} fetches its {@code FlowDef} via {@link FetchFlowActivity}: an Activity
     * call recorded in this workflow's own history, not inline data, so a node's real HTTP/MONGO/MAP/GEN config
     * is never shipped as part of the flow graph itself — only fetched on demand, per node, when this node
     * actually runs. A node with no {@code taskRef} (the escape hatch for a genuinely one-off config) still uses
     * its own inline {@code fields} unchanged.
     */
    private NodeResult runTask(TaskNode task, FlowRunRequest req, Map<String, NodeResult> results, String keyPrefix) {
        return withPark(keyPrefix + task.id(), () -> {
            Map<String, Object> inputs = InputResolver.resolve(task.in(), req.inputs(), results);
            int version = task.taskRef() != null ? pinnedVersions.getOrDefault(task.taskRef(), 1) : 1;
            Map<String, Object> fields = task.fields();
            if (task.taskRef() != null) {
                FetchTaskActivity fetchTask = Workflow.newActivityStub(FetchTaskActivity.class,
                        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());
                TaskDef def = fetchTask.fetch(task.taskRef(), version);
                fields = def.fields();
            }
            TaskActivity activity = Workflow.newActivityStub(TaskActivity.class, activityOptions(task.onError()));
            // runNo is a placeholder until a case/attempt concept exists above this engine; not this milestone's concern.
            TaskRequest request = new TaskRequest(task.kind(), task.taskRef(), version,
                    Workflow.getInfo().getWorkflowId(), task.id(), 1, inputs, fields);
            TaskResult result = activity.execute(request);
            return new NodeResult(result.outcome(), result.outputs());
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
     * taskRefs through {@link ResolveTaskActivity}, flowRefs through {@link ResolveFlowActivity}: two different
     * {@code DocType}s in the same registry, two different activities, exactly how RECIPE refs (L1's own
     * registry) and FLOW refs were never resolved through the same call either, before L1 was deleted.
     */
    private Map<String, Integer> resolveVersions(FlowDef flow) {
        List<String> taskRefs = flow.nodes().stream()
                .flatMap(n -> n instanceof TaskNode t && t.taskRef() != null ? Stream.of(t.taskRef()) : Stream.<String>empty())
                .distinct()
                .toList();
        // A SubflowNode with its own pinned version() needs no resolution at all -- it already knows its number,
        // forever, regardless of whatever the registry currently calls latest (§9's "v1 caller, v2 caller, side
        // by side" case: only an UNPINNED ref ever asks "what's latest").
        List<String> flowRefs = flow.nodes().stream()
                .flatMap(n -> n instanceof SubflowNode s && s.version() == null ? Stream.of(s.flowRef()) : Stream.<String>empty())
                .distinct()
                .toList();

        Map<String, Integer> resolved = new HashMap<>();
        if (!taskRefs.isEmpty()) {
            ResolveTaskActivity registry = Workflow.newActivityStub(ResolveTaskActivity.class,
                    ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());
            resolved.putAll(registry.resolveVersions(taskRefs));
        }
        if (!flowRefs.isEmpty()) {
            ResolveFlowActivity registry = Workflow.newActivityStub(ResolveFlowActivity.class,
                    ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());
            resolved.putAll(registry.resolveVersions(flowRefs));
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
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(attempts)
                        .setDoNotRetry(TaskFailureType.UNKNOWN.name(), TaskFailureType.BUSINESS.name(),
                                TaskFailureType.PERMANENT.name())
                        .build())
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
