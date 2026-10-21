# LIH — Design Overview (L1 + L2)

Single consolidated reference. The split docs (`hld.md`, `l2-lld.md`,
`temporal-operations.md`, `decision-log.md`) go deeper on specific areas;
this one is meant to stand alone.

## 1. Problem

Every lender integration in lendercore is hand-written Java — request
building, response extraction, status polling, callback handling, retries —
duplicated per lender with their own bugs. Adding a lender or changing one
lender's API means a code change and redeploy.

## 2. Direction

LIH replaces that with one generic interpreter running lender flows from
published config, on Temporal. Temporal was chosen over BPMN/Zeebe because it
gives durable execution, timers, and synchronous request/response into a
running case (via Updates) without hand-rolled persistence or a separate
async-service + queue layer.

## 3. The L1/L2 boundary

- **L2 (flow)** — the shape of a case: which steps exist, what order they run
  in, where callbacks/status-pulls plug in, how branches/parallel work. L2
  never looks at lender data to decide what happens next — only at outcome
  labels a task produces.
- **L1 (task)** — everything lender-specific for one step: endpoint,
  protocol, auth, how to build the request, how to read the response, how a
  response maps to an outcome label. A lender API change is an L1 config
  publish; a flow-shape change is an L2 edit.

**Build order**: L2 first — designed, built, tested as a standalone concern —
before L1 specifics (config schema, CEL DSL, operation kinds beyond REST) are
worked. The `OperationDef`/`L1`/`L1Registry` in this repo today are a
deliberately thin stub, just enough for L2 to have something to call against —
not the target L1 design. L1 specifics stay out of scope until L2 is
finalized and tested.

## 4. L2 engine — current implementation (this repo)

- **`FlowDef`** — plain JSON: name, an ordered `steps` list, a map of named
  `events`. Loaded once per lender/flow from the classpath (`L1Registry`).
  Step types: `CALL` (run an L1 op), `DECIDE` (SpEL over vars), `PERSIST`/
  `NOTIFY` (hand vars to a named sink), `WAIT` (park until a SpEL condition is
  true). Every step can carry a `when` guard; `CALL` carries `retries`/
  `timeoutSec`.
- **`FlowWorkflowImpl`** — one generic interpreter for every lender/flow,
  state is a single `vars` map. `run()` is the `@WorkflowMethod`; `event()`
  is an `@UpdateMethod` used for both inbound callbacks and status-pull,
  queued behind a `ready` flag and serialized by a `WorkflowLock`; `state()`
  is a `@QueryMethod` snapshot for introspection.
- **`StepActivities`/`StepActivitiesImpl`** — `call(lenderSlug, op, vars)`
  looks up the `OperationDef`, builds the request, executes via the protocol
  adapter, applies `extract`. Transport failure → retryable
  `RuntimeException`; config problem → non-retryable `ApplicationFailure`
  tagged `ConfigError`.
- **`Expr`** — one evaluator for `DECIDE`/`when`/`until`: SpEL via
  `SimpleEvaluationContext` (no types/constructors/bean references reachable
  from config), deterministic by construction so it's safe inside workflow
  code.
- **`RestAdapter`** — real HTTP via `java.net.http.HttpClient`. `${NAME}`
  placeholders resolve from system property then env var at call time —
  secrets/env values are never baked into config.
- **Temporal wiring** — one task queue per lender (`"lender-{slug}"`), one
  shared `WorkerFactory` for all lenders' queues in-process, workflow id
  `{lender}-precheck-{customerId}-{loanRequestId}` with
  `WorkflowIdReusePolicy.REJECT_DUPLICATE`.
- **HTTP surface** (`ApiController`/`LihPrecheckService`) — same three
  endpoints lendercore already exposes (`POST /precheck`, `GET /precheck`,
  `POST /v1/callback`), now thin shells over an L2 flow.

## 5. L2 flow-config model — target design (node/edge DAG)

The target replaces the linear step-list above with a node/edge graph, so a
case can branch to different next-steps based on what a task reports, not
just skip-or-run-next.

```json
{
  "flow": "fed-precheck",
  "start": "fanout",
  "nodes": [
    { "id": "fanout", "kind": "PARALLEL", "to": ["pan", "unName", "dobName"] },
    { "id": "pan", "kind": "TASK", "ref": "fed.precheck.pan@1",
      "onError": { "retries": 3, "then": "PARK" } },
    { "id": "unName", "kind": "TASK", "ref": "fed.precheck.unName@1",
      "onError": { "retries": 3, "then": "PARK" } },
    { "id": "dobName", "kind": "TASK", "ref": "fed.precheck.dobName@1",
      "onError": { "retries": 3, "then": "PARK" } },
    { "id": "aggregate", "kind": "JOIN", "join": "ALL",
      "from": ["pan", "unName", "dobName"] },
    { "id": "decide", "kind": "TASK", "ref": "fed.precheck.decide@1" },
    { "id": "approved", "kind": "END", "status": "SUCCESS" },
    { "id": "rejected", "kind": "END", "status": "FAILURE" }
  ],
  "edges": [
    { "from": "aggregate", "to": "decide" },
    { "from": "decide", "to": "approved", "on": "OK" },
    { "from": "decide", "to": "rejected", "on": "FAIL" }
  ],
  "events": {
    "statusCallback": { "ref": "fed.precheck.statusCallback@1" }
  }
}
```

Key significance:

- **`ref`** on a `TASK` node — the L1 recipe it runs. L2 never reads lender
  fields here, only gets back an outcome label.
- **`onError`** — `retries` then `PARK` on exhaustion. A parked node blocks
  in place; it does not propagate failure upward on its own.
- **`to`** on `PARALLEL` — every listed node runs concurrently,
  unconditionally (not a branch choice).
- **`from`** / **`join`** on `JOIN` — `"ALL"` is the only policy built for the
  POC: cyclic-barrier-like, fires once every incoming branch succeeds. A
  branch that parks doesn't cancel its siblings or fail the join — it just
  holds it open until resolved; siblings that already succeeded sit idle,
  not re-run.
- **`on`** on an edge — the outcome label that selects it; exactly one fires.
  Omitted on edges leaving `PARALLEL`/`JOIN` since those aren't label-based.
- **`status`** on `END` — terminal case status recorded when reached.
- **`events{}`** — stays a flow-level map, not a field on `WAIT`. A callback
  can arrive before the flow reaches its `WAIT` node, and some events
  (status-pull) are caller-driven and repeatable, not one-shot — the engine
  buffers independent of node position regardless of schema shape.

**Decided and dropped**: a `project` key (callback writes a queryable
projection independent of flow state) was proposed in early design review
and then dropped — the L1 worker/CEL design already models Mongo writes as a
plain operation kind (`MONGO`) inside an L1 recipe, so a projection write is
just another recipe step. Keeping `project` at the L2 layer would duplicate
that and give L2 a reason to know about lender-specific collections/fields,
which it's supposed to stay unaware of. An event now only ever does
`{ "ref": "<L1 recipe>" }`.

**Not yet built**: `CHILD` node kind + `END.next` (chain to an independent
new case) and the cross-case rendezvous pattern (combine two already-running
independent cases via a Mongo correlation record + event, since Temporal has
no native cross-workflow join) were proposed but not locked into the schema.

## 6. Pluggable execution — the `TaskExecutor` boundary

L2 is meant to be a general-purpose, plug-and-play interaction layer with
Temporal, not something lending-specific that happens to tolerate a
compatible worker. The plug-in point is a plain interface, not a shared task
queue contract:

```java
public interface TaskExecutor {
    ExecutionResult execute(String ref, Map<String, Object> inputs);
}
public record ExecutionResult(String outcome, Map<String, Object> outputs) {}

class RetryableTaskException extends RuntimeException {}  // library retries per ActivityOptions
class PermanentTaskException extends RuntimeException {}  // library parks it, never retried
```

The consuming application implements `TaskExecutor` and never imports
`io.temporal.*` — the library's own internal Activity implementation
delegates to whichever executor is registered, in-process, no network hop.
Error/retry intent is entirely the client's call (which exception it
throws); L2 mechanically reacts to *which* exception came back without ever
knowing *why* — mirrors Zeebe's own unawareness of what's inside a job
handler.

The consumer-facing control surface is a thin facade, so even the "start/
query/send-event" interactions never touch Temporal types directly:

```java
public interface FlowEngine {
    void start(String flowRef, Map<String, Object> vars);
    Map<String, Object> event(String caseId, String name, Map<String, Object> payload);
    Map<String, Object> state(String caseId);
}
```

**Decided:**

- **Full DAG vocabulary is universal.** `PARALLEL`/`JOIN`/`CHILD`/`events`/
  `onError` are properties of the generic interpreter, not scoped per
  executor — every consumer gets the same flow-shape capabilities; only
  which `TaskExecutor` runs a given `ref` varies.
- **Routing differs per flow.** A flow carries an `executor` id (default
  `"default"` if only one is registered); the library holds a
  `Map<String, TaskExecutor>` and resolves `executors.get(flow.executor)`
  per `TASK` node. One new field, one map lookup — no per-`ref` routing
  table.
- **Flow config is not shared across executors.** Each flow file is 1:1 with
  the executor it targets — same shape as today (one flow file per lender),
  generalized to "one flow file per registered executor."
- **No versioning ownership for BYO executors.** Once an executor is
  plugged in via the SPI, L2 doesn't track or care about the internal
  version/correctness of its implementation — that's fully the
  implementer's concern.
- **Data stored is whatever the executor returns.** L2 imposes no PII/raw-
  payload policy on `ExecutionResult.outputs()` — whatever an executor hands
  back gets stored (e.g. lands in Temporal history). That's the executor's
  choice/responsibility, not L2's to enforce.

## 7. Deployment model — library and hosted service, not library-vs-service

Two different kinds of consumer need two different deployment modes of the
*same* engine, schema, and `TaskExecutor` SPI:

- **Embedded library** — a consumer that already runs its own JVM process
  and needs the lowest possible latency (zero network hop between
  orchestration and task execution) depends on L2 directly, implements
  `TaskExecutor` in-process, and the library creates/owns the
  `WorkerFactory`/`Worker` inside that same JVM. This is lendercore's model
  today, and the default for any latency-sensitive, code-capable consumer.
- **Hosted multi-tenant service** — a self-serve platform where users author
  flows through a UI have no process of their own to embed anything into.
  For them, L2 runs as a centrally-hosted service (one deployment, one
  Temporal cluster, namespace-per-tenant — see §8) that they never see
  Temporal or deploy anything for. Their `TASK` nodes are satisfied by a
  **built-in generic executor** — functionally today's L1 stub (REST call +
  transform + extract, driven entirely by config, no custom code). A
  consumer that wants custom logic without embedding registers a
  network-facing `TaskExecutor` (webhook/gRPC) against this same hosted
  service instead of writing their own `TaskExecutor` in-process.

Neither mode changes the node/edge schema or the `TaskExecutor` contract —
"library" and "hosted service" are deployment choices per consumer, not two
different architectures for L2 itself.

## 8. Multi-tenancy — namespace per team

Chosen: **namespace-level** multi-tenancy on one shared Temporal cluster
(not bring-your-own-cluster). This requires no change to L2's own code — only
`TemporalConfig`/`LihProperties` config (`target` stays the shared cluster
address, `namespace` varies per embedding app/team).

- Task queues and workflow IDs are namespace-scoped in Temporal itself — two
  teams can reuse the same queue name or workflow-id scheme with zero
  collision risk, enforced by the platform, not by convention.
- A future UI's access control maps directly to namespace membership —
  "which users/teams can see/act on which cases" = "which namespaces can
  this user query."
- **Hard boundary**: no cross-namespace workflow operations. The
  cross-case rendezvous pattern (§5) only works within one team's own
  namespace — two different teams can never compose cases across it.
- Namespace provisioning is a low-frequency, ops-driven action per new team
  onboarding — not self-service, not worth automating yet.
- Namespaces isolate data, not cluster throughput — one team's workflow
  volume can still pressure history/matching latency for everyone sharing
  the cluster. Temporal per-namespace quotas are the lever; this is one of
  the open items under network topology/low latency (§10).

## 9. The future UI layer

Not built yet; two distinct jobs identified for it, deliberately deferred:

- **Ops/audit UI** — gate direct Temporal access to services and some
  developers only; everyone else interacts through this layer, which is
  where RETRY/SKIP/FAIL actions on parked nodes get an audit trail. Today
  (POC), anyone can perform these actions with no gating — acceptable for
  now, not the end state.
- **Self-serve flow-authoring UI** — the platform ambition: users create
  workflows via UI, not by hand-editing JSON on the classpath. This is what
  makes config-publish/versioning (§10) load-bearing rather than optional.

A parked case today is only discoverable by polling — no proactive alerting
yet; accepted as fine for v0. Whether `PARK`/timeout behavior should be
uniform across node types is still open (flagged, not yet decided).

## 10. Open gaps / decision log

**Blocking-shaped** (affect whether the first real flow actually works):

- **Callback correlation key** — Axis's Timble callback only carries
  `customer_id`, nothing unique per run. Needs either a real correlation
  field from the lender, or an explicit "reject concurrent precheck for the
  same customer" rule.
- **Source of truth** — workflow vs. Mongo `PrecheckEntity` can drift if a
  `PERSIST` step fails after the workflow has moved on; design leans
  workflow-as-authoritative with Mongo as an idempotent projection.

**Design gaps, not yet needed for the POC but will block the next flow or
the platform ambition:**

- **Outcome-label vocabulary** — free-form per-task labels vs. a fixed
  platform set (`OK`/`FAIL`/`PENDING` reserved).
- **No node/edge graph yet** — what's built is a linear step list with
  `when` guards, not the target DAG (§5).
- **`DECIDE` blurs the L1/L2 boundary** — it evaluates raw lender fields
  inline today; that classification belongs in L1's outcome mapping.
- **No `PARALLEL`/`JOIN` implemented** — join policy decided (`ALL`, §5) but
  not built.
- **No version pinning / runtime config publish** — `L1Registry` loads
  everything once at startup from the classpath; no per-case snapshot, no
  `(ref, version)` keying. Now a precondition for self-serve authoring
  (§9), not just a later nice-to-have.
- **`onError`/`PARK` not implemented** — only a flat retry count exists;
  `runSteps` has no try/catch, so an exhausted retry crashes the whole
  workflow execution today rather than parking.
- **`respond`/`correlate` not generalized as config** — `respond` is
  effectively `okVar` → 200/400 already, just not named that way;
  `correlate` is hardcoded as a visibility-query workaround, not a config
  concept, and can only key on `customerId` today.
- **POLL/DELAY node kind** — every status-check today is caller-driven;
  nothing can poll on a timer by itself.
- **Batch/case-less flow kind** — cron/EOD-style work has no home in the
  current per-case workflow model.
- **Evaluator is SpEL, not CEL** — L1 design settled on CEL for its safety
  properties; L2's `Expr` uses restricted SpEL. Worth reconciling once L1
  work starts so there aren't two expression languages for no reason.

**Resolved / not worth re-litigating:**

- SpEL acceptable for L2's own (shrinking) guard/decide logic, for now.
- REST-only protocol scope acceptable for the POC — SOAP/SFTP are
  registered as named adapters that fail loudly, so adding them later is
  config-compatible, not breaking.

## 11. Rollout model

Once proven, rollout is a traffic-diversion slice: new cases for a lender
start on LIH, in-flight cases for that lender stay on the old lendercore
path until they complete — avoids migrating in-flight workflow state
between the two systems.

## 12. L1 — deferred

L1 specifics (CEL DSL, operation kinds beyond REST, outcome-mapping rules,
config-publish/approval flow) are intentionally out of scope until L2 is
finalized and tested. What's settled for L1 is only the boundary (§3) and
that an L1 recipe is where lender-specific logic, Mongo writes
(`MONGO` operation kind), and outcome classification all live — never in L2.
