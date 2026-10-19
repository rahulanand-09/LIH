# `com.rupeek.flow` — Low-Level Design

This supersedes the L1/L2 two-layer split described in `design.md`/`hld.md`/`l2-lld.md`/`decision-log.md`. Those
docs record the *design process* that got here and are kept for that history; this doc describes what is
actually built and running on `feature/l2-graph-engine` today. If the two disagree, this doc and the code win.

## 1. Why this exists

The original plan was to build L2 (a flow/graph layer) on top of the already-built L1 (a per-lender recipe
engine) — two fixed tiers. That was scrapped in favour of one recursive engine: a flow is a graph of nodes, and
a node is either a **leaf TASK** (one Activity call) or a **SUBFLOW** (a reference to another whole flow, run as
a Temporal child workflow, to any depth). There is no fixed two-tier split — "Axis Precheck → Axis Dedupe →
(fetch customer, check mongo, call Axis, save)" would just be three levels of the same shape, if Dedupe were
ever decomposed that far. For the real Axis precheck flow built so far, it wasn't: every node delegates a whole
**L1 recipe** to the already-proven `com.rupeek.l1` engine (see §7) rather than decomposing recipes into
flow-level leaves.

Hard constraint this design works within, not around: Temporal requires workflow code to be deterministic and
replayable, so any real side effect (an HTTP/SOAP/SFTP call, a Mongo write, even `UUID.randomUUID()`) can only
happen inside an Activity, never directly in workflow code. Every leaf TASK bottoms out in one Activity call;
only SUBFLOW composition happens in workflow code.

## 2. Package map

| Package | What's in it |
|---|---|
| `com.rupeek.flow.model` | The node/edge schema — plain data, no Temporal imports. `FlowDef`, the sealed `Node` interface and its six records, `Edge`, `OnError`, `NodeResult`, `OperationKind`. |
| `com.rupeek.flow.activity` | The engine's own generic leaf-TASK contract (`TaskActivity`/`TaskRequest`/`TaskResult`/`TaskFailureType`) — used by non-`RECIPE` node kinds. Nothing in the Axis precheck flow uses this today (see §7); kept for a future non-L1 operation kind. |
| `com.rupeek.flow.workflow` | The interpreter itself: `FlowWorkflow` (the one `@WorkflowInterface`, reused at every depth), `FlowWorkflowImpl`, `FlowRunRequest`, `InputResolver`, `ResumeAction`, `FlowState`. |
| `com.rupeek.flow.registry` | `ResolveFlowActivity` (the engine's own placeholder version-pinning activity, for non-`RECIPE` refs) and `FlowDefLoader` (loads a `FlowDef` from a classpath JSON resource — see §9). |
| `com.rupeek.l1.*` | Untouched. The real recipe/operation engine — CEL, HTTP/MONGO/MAP/GEN executors, registry, publish pipeline, golden tests. `com.rupeek.flow` depends on exactly two types from it: `com.rupeek.l1.api.TaskActivity` and `com.rupeek.l1.api.RegistryActivity` (see §7). |

Deleted entirely as dead code superseded by this package: `com.rupeek.lender.*` (old DTOs/entities) and
`com.rupeek.lih.*` including the old linear-step-list `com.rupeek.lih.l2.*` stub. Nothing in `com.rupeek.l1`
referenced either package (only a few Mongo `_class` string literals mention `com.rupeek.lender.entity.*` by
name, as plain data — not a compile-time dependency), so the deletion was a pure subtraction.

## 3. Class design — the node/edge schema

`com.rupeek.flow.model` is plain data: no Temporal imports, no behaviour, nothing that can't be Jackson-
(de)serialized. A `FlowDef` is a graph: a list of `Node`s and a list of `Edge`s connecting them by id, plus which
node id to start at. `Node` is a **sealed interface** — Java enumerates its own permitted subtypes, so a
`switch` over a `Node` (§4's `execute()`) is exhaustive and the compiler, not a runtime default branch, catches a
missing case the day a seventh node kind is added:

```java
public record FlowDef(String flowRef, int version, String start, List<Node> nodes, List<Edge> edges) {}

public sealed interface Node permits TaskNode, SubflowNode, WaitNode, ParallelNode, JoinNode, EndNode {
    String id();
}
```

Six records implement it — two are the composition primitives the whole engine is built from, four are fixed
graph-control kinds:

| Record | Role | Fields | Notes |
|---|---|---|---|
| `TaskNode` | **leaf** — one Activity call | `id, kind, taskRef, fields, in, onError` | `kind` (`OperationKind`) selects *what* the Activity does; `RECIPE` is the one used today (§8) |
| `SubflowNode` | **composite** — another whole `FlowDef`, run recursively | `id, flowRef, in, onError` | `flowRef` is looked up in `FlowRunRequest.registry()` at runtime, not resolved at parse time (§7) |
| `WaitNode` | suspend for an external signal | `id, event, timeout, onTimeoutOutcome` | §8's `waitCallback` |
| `ParallelNode` | fan out | `id, branches` (list of node ids) | handled inline in the interpreter loop, not via `execute()` (§5) |
| `JoinNode` | fan in, `"ALL"` policy only | `id, policy, from` (list of node ids) | ditto |
| `EndNode` | terminal | `id, status, outputs` | `status` becomes this flow's outcome to its caller; `outputs` optionally hands data back too (below) |

`TaskNode` and `SubflowNode` are the only two things a flow author ever really chooses between — "is this one
primitive operation, or is it worth its own graph?" — which is the whole point of the recursive redesign: there
is no fixed depth limit and no separate "task-engine" vs. "flow-engine" type, just one `Node` sealed interface
used at every level. A `TaskNode`'s `taskRef` names a shared, versioned definition (reused across flows, e.g.
every `RECIPE` ref); `fields` inlines a one-off operation's own config directly when nothing else would reuse it.

`EndNode` originally only carried a `status` label; a second constructor (`EndNode(id, status)` → `outputs =
Map.of()`) keeps that the common case, since most flows' callers only need the outcome. The three-arg form exists
because a `SUBFLOW`-based flow needs to hand data back, not just a label — Axis Dedupe's `applicantId`/
`clientLoanId` are read by Timble's own `audit` node (§11), and the only way a parent can see a child's data is
through the child's own `EndNode.outputs()`, resolved the exact same way a `TaskNode`'s `in` is (next table).

**No shared mutable state.** `FlowWorkflowImpl.run()` keeps one `Map<String, NodeResult> results`, written once
per node, keyed by that node's own id. A node's `in` map (or an `EndNode`'s `outputs` map) is small expressions
resolved by `InputResolver` against already-completed ancestors or the flow's own input — never a forward or
sideways read, and never a shared context two concurrent `PARALLEL` branches could collide on:

| Path | Resolves to |
|---|---|
| `$.in.<field>` | the flow's own input map |
| `$.<nodeId>.outputs.<field>` | one field of an already-completed node's outputs |
| `$.<nodeId>.outputs` | that node's whole outputs map (e.g. passing a raw callback payload straight through, §7/§8) |
| `$.<nodeId>.outcome` | that node's outcome label |
| anything not starting with `$.` | a literal, passed through verbatim |

This is deliberately not an expression language — CEL already lives inside an L1 recipe's own templates; this
resolver only wires one node's output, or a plain constant, into another node's input. There is no nested
dot-path (`$.a.outputs.b.c` doesn't exist), no typed literal (a bare `"true"` is the string `"true"`, not a
boolean — `axis.decomposed.statusPersist.saveFinal`, §11, coerces it with `input.approvedFlag == 'true'` on the
recipe side), and no cross-sibling read except through an explicit `$.in.<field>` the parent flow wired down —
e.g. `timble`'s `applicantId` (§11) isn't reachable from inside the `timble` subflow any other way, since
`dedupe` and `timble` are siblings, not ancestor/descendant.

```java
public record Edge(String from, String on, String to) {}   // "on" is null on edges leaving PARALLEL/JOIN
public record OnError(int retries, String then /* "PARK" only */) {}
public record NodeResult(String outcome, Map<String, Object> outputs) {}
```

`Node` carries a Jackson `@JsonTypeInfo(use = Id.NAME, property = "nodeType")` + `@JsonSubTypes` so a flow can be
deserialized from JSON (§9) — `nodeType` is a separate discriminator from `TaskNode.kind()` (which names the
*operation* kind, e.g. `RECIPE`/`HTTP`), not the node's own Java type. Concretely, the JSON→Java mapping is:

| JSON `"nodeType"` | Java record |
|---|---|
| `"TASK"` | `TaskNode` |
| `"SUBFLOW"` | `SubflowNode` |
| `"WAIT"` | `WaitNode` |
| `"PARALLEL"` | `ParallelNode` |
| `"JOIN"` | `JoinNode` |
| `"END"` | `EndNode` |

**No shared mutable state.** `FlowWorkflowImpl.run()` keeps one `Map<String, NodeResult> results`, written once
per node, keyed by that node's own id. A node's `in` map is small expressions resolved by `InputResolver`
against already-completed ancestors or the flow's own input — never a forward or sideways read, and never a
shared context two concurrent `PARALLEL` branches could collide on:

| Path | Resolves to |
|---|---|
| `$.in.<field>` | the flow's own input map |
| `$.<nodeId>.outputs.<field>` | one field of an already-completed node's outputs |
| `$.<nodeId>.outputs` | that node's whole outputs map (e.g. passing a raw callback payload straight through, §7/§8) |
| `$.<nodeId>.outcome` | that node's outcome label |
| anything not starting with `$.` | a literal, passed through verbatim |

This is deliberately not an expression language — CEL already lives inside an L1 recipe's own templates; this
resolver only wires one node's output, or a plain constant, into another node's input.

## 4. Runtime walkthrough

`FlowWorkflowImpl.run(FlowRunRequest req)`:

1. Build `byId` (every node in `req.flow()`, by id) and an empty `results` map.
2. **Resolve versions** for this flow's own direct refs — once, here (§6).
3. Loop from `flow.start()`: look up the current node, dispatch by its kind (`EndNode` returns immediately;
   `ParallelNode`/`JoinNode` are handled inline in the loop, §5; everything else goes through `execute()` →
   `runTask`/`runSubflow`/`runWait`), write the result into `results` and `nodeStatus`, then follow the edge
   whose `on` matches the returned outcome (`nextNodeId`) — or, leaving a `PARALLEL`/`JOIN`, the one edge with no
   `on` at all (`nextUnconditional`).

`FlowWorkflow`'s four methods, same Java type at every recursion depth:

```java
@WorkflowMethod NodeResult run(FlowRunRequest req);
@SignalMethod  void resume(String nodeId, ResumeAction action);   // operator RETRY/SKIP on a parked node
@SignalMethod  void event(String name, Map<String,Object> payload); // external callback, buffered (§8)
@QueryMethod   FlowState query();                                  // per-node status, for ops traversal
```

## 5. PARALLEL / JOIN

```java
case "PARALLEL": for each branch id → Async.procedure(() -> { write its NodeResult into `results` when done });
                 then follow the one unconditional edge out of PARALLEL (→ the JOIN)
case "JOIN":     Workflow.await(() -> join.from() all present in `results`);
                 then follow the one unconditional edge out of JOIN
```

A branch that parks (§6) is simply a suspended coroutine — its `Promise` is never completed, never failed. That
is exactly why `Workflow.await(() -> ... allMatch(results::containsKey))` is correct here and `Promise.allOf`
would not have been: a failed promise from one branch would cancel/fail the others, which is not what "one
branch parked, waiting on an operator" is supposed to do to its siblings.

## 6. PARK — a suspend, not a thrown failure

Every leaf (`runTask`/`runRecipeTask`/`runSubflow`) runs through one shared wrapper:

```java
private NodeResult withPark(String nodeId, Supplier<NodeResult> attempt) {
    while (true) {
        try {
            return attempt.get();
        } catch (ActivityFailure | ChildWorkflowFailure e) {
            nodeStatus.put(nodeId, "PARKED");
            Workflow.await(() -> pendingResume.containsKey(nodeId));
            ResumeAction action = pendingResume.remove(nodeId);
            if ("SKIP".equals(action.action())) return new NodeResult(action.outcome(), Map.of());
            // RETRY: loop back, fresh attempt budget
        }
    }
}
```

An exhausted activity retry, or an unexpected child-workflow failure, is caught *inside* the node itself — it
never becomes a thrown exception the graph has to route on. The node marks itself `PARKED` (visible via
`query()`), and blocks on a plain `Workflow.await` for an operator's `resume` signal. Two consequences, both
deliberate:

- **A `JOIN` sibling parking doesn't fail the join** (§5) — it's a pending `Promise`, not a failed one.
- **A parked grandchild is resumed by addressing its own workflow id directly** (`{parentWorkflowId}:{nodeId}`,
  §7), found by traversal — the parent is just blocked on `child.run(...)`'s return, with no idea anything is
  parked underneath it. There's no correlation registry anywhere in this design.

## 7. SUBFLOW / child-workflow recursion

```java
ChildWorkflowOptions.newBuilder()
    .setWorkflowId(Workflow.getInfo().getWorkflowId() + ":" + subflow.id())
    .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)   // NOT the default (TERMINATE)
    .build();
```

`ABANDON` is not optional: Temporal's default (`TERMINATE`) would kill a parked grandchild the instant its
parent completes or retries. Before starting, `runSubflow` checks the runtime cycle/depth backstop (§9) and
builds the child's own `FlowRunRequest` (depth+1, lineage+1). The child's `run()` return value — its `END`
status — *is* this node's outcome; there is no separate "ask the child its status" step.

`SubflowRecursionTest` and `ParkAndResumeTest`'s nested-grandchild test prove the mechanics against toy flows.
§11's production `axis-precheck.json` doesn't use `SubflowNode` — every node there is a flat `RECIPE`-kind `TASK`,
one whole L1 recipe per node. §11b's `axis-precheck-decomposed.json` is where `SubflowNode` is real and
executing: the top-level flow's major blocks (`dedupe`, `timble`, `timbleCallback`, `status`) are each a
`SUBFLOW` running as a genuine Temporal child workflow, and `status` itself spawns a grandchild
(`status-persist`) — proving 3 levels of recursion against real L1 recipes, real Mongo, and WireMock, not just
toy fixtures.

## 8. The bridge to real L1: `OperationKind.RECIPE`

A `TaskNode` is either a flow-native generic operation (`HTTP`/`SOAP`/`SFTP`/`MONGO`/`MAP`/`GEN`, executed via
the engine's own `com.rupeek.flow.activity.TaskActivity`) or `RECIPE` — delegate the whole node to a published
L1 recipe, via L1's own real activities on their own task queue:

```java
com.rupeek.l1.api.TaskActivity l1 = Workflow.newActivityStub(com.rupeek.l1.api.TaskActivity.class,
        ActivityOptions.newBuilder()
                .setTaskQueue("lih-l1")
                .setRetryOptions(RetryOptions.newBuilder()
                        .setDoNotRetry(L1FailureType.L1_BUSINESS.name(), L1FailureType.L1_UNKNOWN.name(),
                                L1FailureType.L1_PERMANENT.name())
                        .build())
                .build());
l1.runTask(new com.rupeek.l1.api.TaskRequest(task.taskRef(), version, caseId, runNo, inputs));
```

`setTaskQueue("lih-l1")` is explicit and deliberate, independent of whichever queue the flow workflow itself
runs on — L2 (this engine) and L1 can be two separate processes, connected only by that task queue, even though
the test harness runs both in one worker for simplicity. `runNo` is not part of the engine's own contract; it
travels as a plain `inputs.runNo` value, same as every other input, exactly as `l1-for-l2.md` documents — the
engine stays ignorant of what it means. Every Axis precheck recipe used today is **unchanged, already-proven L1
config**: `axis.precheck.{dedupe,timble,timbleCallback,status}`, `precheck.notify.{verified,failed}`.

Version resolution (§9) follows the same split: `resolveVersions` collects `RECIPE` refs separately from
everything else, and resolves them through L1's own real `RegistryActivity.resolveRecipes` (same task queue),
not the engine's placeholder.

## 9. Version pinning and the cycle/depth guard

```java
private Map<String, Integer> resolveVersions(FlowDef flow) {
    // recipeRefs -> com.rupeek.l1.api.RegistryActivity.resolveRecipes(...)   (real L1, lih-l1 queue)
    // otherRefs  -> com.rupeek.flow.registry.ResolveFlowActivity.resolveVersions(...)  (engine placeholder)
}
```

Called once, near the top of **every** flow's own `run()` — not once for the whole tree. A flow only ever
resolves the refs its *own* node list references directly, never transitively. This is deliberately recursive
and lazy: a `SUBFLOW` invoked after a long `WAIT` legitimately resolves its own refs at *its* start, which may
differ from what its parent resolved at the case's start — proven directly by
`VersionPinningAndCycleGuardTest.eachLevelResolvesItsOwnVersionsIndependentlyAtItsOwnStart`.

Cycle/depth is a pure, deterministic runtime check inside `runSubflow`, a backstop against a cycle a (not yet
built) publish-time check might miss: `depth` increments per `SUBFLOW` start, capped at `MAX_DEPTH = 25`;
`flowRefChain` carries every flowRef in the lineage from the root, and a child's own `flowRef` reappearing in it
fails fast (`ApplicationFailure`, type `ConfigError`) instead of recursing forever —
`VersionPinningAndCycleGuardTest.aSelfReferencingSubflowFailsFastInsteadOfRecursingForever` proves it.

## 10. `FlowDefLoader` — config, not just Java fixtures

```java
FlowDef flow = FlowDefLoader.loadFromClasspath("/flows/axis-precheck.json");
```

A plain Jackson read (`JavaTimeModule` registered, for `WaitNode.timeout()`), not a registry: no versioning, no
publish-time validation, no pinning lifecycle — §9's `ResolveFlowActivity`/`RegistryActivity` split is the seam
a real published/versioned flow store would eventually plug into. `AxisPrecheckFlowTest` loads the real Axis
precheck flow this way, from `src/main/resources/flows/axis-precheck.json` — the config is what the test
actually runs, not a separate document that could drift from it.

## 11. Axis precheck — the flow itself

`src/main/resources/flows/axis-precheck.json`, 7 TASK nodes (all `RECIPE`) + 1 WAIT + 3 END:

| id | recipe (`taskRef`) | outcomes → edge |
|---|---|---|
| `dedupe` | `axis.precheck.dedupe` | `IN_PROGRESS`→skipped, `INITIATED`/`ALREADY_INITIATED`/`REJECTED`→timble |
| `timble` | `axis.precheck.timble` | `INITIATED`/`ALREADY_INITIATED`→waitCallback, `REJECTED`→rejected |
| `waitCallback` | — (WAIT for event `timbleCallback`) | `RECEIVED`→timbleCallback |
| `timbleCallback` | `axis.precheck.timbleCallback` | `TIMBLE_OK`/`TIMBLE_FAILED`→status, `NO_PRECHECK`→rejected |
| `status` | `axis.precheck.status` | `SUCCESS`→notifyOk, `FAILURE`→notifyFail, `ALREADY_FINAL`→approved, `NO_PRECHECK`→rejected, `PENDING`→**status** (self-loop) |
| `notifyOk` | `precheck.notify.verified` | `NOTIFIED`/`NOT_ACCEPTED`→approved |
| `notifyFail` | `precheck.notify.failed` | `NOTIFIED`/`NOT_ACCEPTED`→rejected |

`timbleCallback`'s `in` is `{"event": "$.waitCallback.outputs"}` — the WAIT node's `NodeResult.outputs()` *is*
the raw callback payload (`pendingEvents.remove(...)`, verbatim), passed straight through as the recipe's
documented `event` input.

Two deliberate simplifications, left for a human pass (both already-open product questions in `l1-for-l2.md`,
not new ones introduced here): `dedupe`/`timble` `REJECTED` routes straight to `rejected` rather than resolving
the still-open P17 decision, and the `PENDING` self-loop on `status` has no inter-poll delay (fine for a test
against WireMock; a real deployment wants a timer between polls, not a busy loop — there is no `DELAY` node kind
yet, deliberately, since nothing has needed one so far).

### 11a. JSON design — how one flow file is put together

Every `flows/*.json` file is a direct JSON rendering of §3's `FlowDef`/`Node` records — there is no separate DSL
or transform step between the file on disk and `FlowDefLoader.loadFromClasspath(...)`'s return value. Reading
`axis-precheck-decomposed-timbleCallback.json` top to bottom:

```json
{
  "flowRef": "axis-precheck-decomposed-timbleCallback",   // FlowDef.flowRef — the key this is looked up by
  "version": 1,                                            // FlowDef.version — not yet enforced (§9), reserved
  "start": "decide",                                        // FlowDef.start — which node id to begin at
  "nodes": [
    { "nodeType": "TASK", "id": "decide", "kind": "RECIPE", "taskRef": "axis.decomposed.timbleCallback.decide",
      "fields": {}, "in": { "event": "$.in.event" }, "onError": { "retries": 3, "then": "PARK" } },
    ...
  ],
  "edges": [
    { "from": "decide", "on": "OK", "to": "latest" },
    ...
  ]
}
```

- `nodeType` picks the Java record (§3's table); `id` must be unique within this one file — ids are only ever
  resolved within their own flow's `byId` map (§4), so `dedupe`'s `core` node and `timble`'s `core` node are
  unrelated despite sharing an id, by design (two separate `FlowDef`s, two separate `results` maps).
- `kind`/`taskRef`/`fields` are `TaskNode`-only; a `SubflowNode` has `flowRef` in that slot instead, and no
  `kind`/`fields`/`taskRef` at all.
- `in` keys name the recipe's own declared input parameter; values are `InputResolver` paths (§3's table) — e.g.
  `"event": "$.in.event"` means "this flow's own `event` input, passed straight to the `decide` recipe's `event`
  parameter unchanged."
- `onError.retries` feeds `RetryOptions.setMaximumAttempts` (§8); `onError.then` is always `"PARK"` today — no
  other value is implemented, so there's no "give up silently" option by construction.
- `edges` are the routing table: `{from, on, to}` means "when node `from` returns outcome `on`, go to node
  `to`." An outcome with no matching edge is a dead end the interpreter would fail on — `FlowDefLoader`
  deserializes the file but does not itself validate every outcome has an edge; the authoring discipline is "list
  every outcome the recipe can actually return" (cross-checked against each recipe's own `outcomes` array, e.g.
  `axis.decomposed.timbleCallback.decide.json`'s `["OK"]`).
- Two edges can share a `from`/`to` pair with a self-loop — `axis-precheck-decomposed-status.json`'s
  `{"from":"savePending","on":"OK","to":"existing"}` re-enters the same flow's `existing` node, polling until
  `decision` stops returning `PENDING`. There is no inter-poll delay node kind yet (§11's existing caveat,
  unchanged by the decomposition).

### 11b. The fully-decomposed, genuinely executable reference

`src/main/resources/flows/axis-precheck-decomposed*.json` is a second, parallel implementation of §11's same
Axis precheck, broken into major blocks per the original ask ("top level with major blocks, then subflows with
every step in between") rather than left as 7 opaque `RECIPE` nodes. Unlike the old 50-node flat reference this
section used to describe (deleted — it only schema-verified, nothing executed it), **every node here is real**:
no new flow-native `HTTP`/`MONGO`/`MAP`/`GEN` executor was written (that stays inside L1 by design, §8); instead
every leaf is a brand-new, one-to-three-step L1 recipe under `l1-config/recipes/axis-decomposed/`, each wrapping
an already-published, already-tested L1 *operation* (not a new operation) — so the whole tree is config, not new
executor code, and `AxisPrecheckDecomposedFlowTest` runs it end to end against real Mongo + WireMock and asserts
the exact same outcomes/documents as §11's simple version.

**Top level — `axis-precheck-decomposed.json`** (7 nodes: 4 `SUBFLOW`, 1 `TASK`, 1 `WAIT`, routing identical to
§11's table):

| id | nodeType | → | outcomes → edge |
|---|---|---|---|
| `dedupe` | SUBFLOW | `axis-precheck-decomposed-dedupe` | `IN_PROGRESS`→skipped, else→timble |
| `timble` | SUBFLOW | `axis-precheck-decomposed-timble` | `INITIATED`/`ALREADY_INITIATED`→waitCallback, `REJECTED`→rejected |
| `waitCallback` | WAIT | event `timbleCallback` | `RECEIVED`→timbleCallback |
| `timbleCallback` | SUBFLOW | `axis-precheck-decomposed-timbleCallback` | `TIMBLE_OK`/`TIMBLE_FAILED`→status, `NO_PRECHECK`→rejected |
| `status` | SUBFLOW | `axis-precheck-decomposed-status` | `SUCCESS`→notifyOk, `FAILURE`→notifyFail, `ALREADY_FINAL`→approved, `NO_PRECHECK`→rejected |
| `notifyOk` | TASK (`RECIPE`) | `precheck.notify.verified` | →approved |
| `notifyFail` | SUBFLOW | `axis-precheck-decomposed-notifyFail` | →rejected |

`timble`'s `in` includes `"applicantId": "$.dedupe.outputs.applicantId"` — data crossing from one sibling
subflow's `EndNode.outputs()` into another sibling's input, wired explicitly by the parent (§3's note on why this
needs an ancestor hop, not a direct sibling read).

**`axis-precheck-decomposed-dedupe.json`** (12 `TASK` + 4 `END`): `core` (fetch customer) → `existing` (find any
prior precheck doc) → `seed`/`ids` (build the stable `clientLoanId`) → `alreadyDone` (already a terminal
dedupe record? → `alreadyInitiated`, forwarding `applicantId`/`clientLoanId`) → `inProgress` (a call already
in flight? → `auditSkip` → `inProgressEnd`) → `call` (build request + call Axis dedupe, one recipe) →
`isRerun` → `save`/`saveRerun` → `initiated` (forwards `applicantId`, `clientLoanId`, `customerId`).

**`axis-precheck-decomposed-timble.json`** (8 `TASK` + 3 `END`): same `core`/`existing`/`seed`/`ids` shape,
`alreadyDone` → `alreadyInitiated` (forwarding `timbleReferenceId`) or `call` (build + call Timble) → `save` →
`audit` → `initiated` (forwarding `timbleReferenceId`). Takes `applicantId` as a flow input from its parent
(the cross-sibling wiring above) since Timble's own audit record needs it.

**`axis-precheck-decomposed-timbleCallback.json`** (5 `TASK` + 3 `END`): `decide` (parse the callback payload) →
`latest` (re-fetch the precheck doc by customer) → `noPrecheck` (→ `noPrecheckEnd` if none found) → `save` →
`okCheck` → `timbleOk`/`timbleFailed`, each forwarding `loanRequestId`.

**`axis-precheck-decomposed-status.json`** (8 `TASK` + 2 `SUBFLOW` + 4 `END`): `existing` → `none` (no record at
all → `noPrecheckEnd`) → `alreadyFinal` (already approved/rejected → `alreadyFinalEnd`, forwarding
`finalStatus`) → `request`/`call` (poll Axis status) → `timbleFlag`/`decision` → on `PENDING`, `savePending`
**loops back to `existing`** (the self-loop from §11a); on `SUCCESS`/`FAILURE`, routes into one of two
**sibling `SUBFLOW` nodes that both point at the same child flow**, `persistSuccess`/`persistFailure` →
`axis-precheck-decomposed-status-persist`, distinguished only by the literal `in` values each passes
(`approvedFlag: "true"`/`"false"`, `precheckStatus: "success"`/`"failure"`) — one nested flow definition, two
call sites, instead of duplicating `saveFinal`/`auditOutcome` per branch the way the old flat decomposition had
to (§11a's JSON-design note on why a graph can't "skip a step," only route — recursion via `SubflowNode` is what
lets two branches *reuse* a shared sub-graph instead of cloning it node-for-node).

**`axis-precheck-decomposed-status-persist.json`** (2 `TASK` + 1 `END`, a *grandchild* of the top-level flow):
`saveFinal` (writes `approved`/final status; the recipe coerces the string `"true"`/`"false"` input into a
boolean, §3's note on literals never being typed) → `auditOutcome` → `done`.

**`axis-precheck-decomposed-notifyFail.json`** (3 `TASK` + 2 `END`): `existing` → `buildProfileJson` → `notify`
→ `notified`/`notAccepted`.

**Two genuine L1 platform constraints surfaced by decomposing this far, both still in force in the merged
recipes under `l1-config/recipes/axis-decomposed/`:**

- **No input may resolve to `null`**, even when "not found" is a legitimate outcome — `RecipeRunner.validateInputs()`
  rejects it regardless of declared type. `axis.decomposed.findPrecheck.json`/`findLatestByCustomer.json` coalesce
  with `"doc": "steps.existing.found ? steps.existing.doc : {}"` rather than letting a missing document surface
  as `null`.
- **A recipe may never return PII-marked data as an output** (`OutputGuard`/`PiiMasker`) — building a lender
  request and calling the lender must happen inside one recipe, never split across a flow-node boundary. The
  first pass here split "build the Axis/Timble request" and "call Axis/Timble" into two separate flow nodes and
  `OutputGuard` correctly rejected the request object crossing that boundary; `axis.decomposed.dedupe.buildAndCall.json`
  and `axis.decomposed.timble.buildAndCall.json` merge both steps into one recipe instead, matching how the
  original hand-written Java already respected this same constraint.

## 12. Testing

| File | Milestone | Proves |
|---|---|---|
| `FlowWorkflowImplTest` | M1 | `TASK`/`END`, edge routing by outcome, no shared mutable state |
| `SubflowRecursionTest` | M2 | `SUBFLOW` → real child workflow, deterministic `{parent}:{nodeId}` id |
| `ParkAndResumeTest` | M3 | PARK-as-suspend, `resume`, nested grandchild resumed by direct traversal |
| `ParallelJoinTest` | M4 | parked branch holds the join open without failing siblings |
| `WaitAndEventTest` | M5 | event buffering regardless of node position, plus a timeout path |
| `VersionPinningAndCycleGuardTest` | M6 | per-level resolution, runtime cycle guard |
| `AxisPrecheckFlowTest` | M7 | the real flow (§11), loaded from real config (§10), against real L1 recipes + real Mongo (Testcontainers) + WireMock — approved / rejected / already-in-progress |
| `AxisPrecheckDecomposedFlowTest` | M7, decomposed | §11b's multi-`SUBFLOW` decomposition, same oracle/fixtures as `AxisPrecheckFlowTest` — proves 3 levels of real child-workflow recursion (top → dedupe/timble/timbleCallback/status → status-persist) produce identical outcomes/Mongo documents to the simple 7-node version |

M1–M6 use toy/synthetic flows and a test-double `com.rupeek.flow.activity.TaskActivity`, so none of them need
real I/O. The two Axis tests are the ones that pull in `L1Components`/`RegistryActivityImpl` (L1's real worker
wiring) and a real `mongo:7.0` Testcontainers instance — expect the first run in a clean environment to spend a
while pulling that image. `AxisPrecheckDecomposedFlowTest` additionally registers a trivial `ResolveFlowActivity`
on the test worker (§9) — omitting it is a silent, zero-log hang, not a visible error, since the `SUBFLOW` nodes'
version-resolution activity task just sits unclaimed forever.
