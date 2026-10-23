# L2 — Low-Level Design

This covers L2 as it exists in this repo today (`com.rupeek.lih.l2`), and
explicitly calls out where it still differs from the target flow-config
design that came out of design review. The gap section is the starting
point for L2 improvement work.

## Current implementation

### Flow definition (`FlowDef`)

A flow is plain JSON: a name, an ordered `steps` list, and a map of named
`events`. Loaded once per lender/flow from `flows/{lender}-{flow}.json` on
the classpath (`L1Registry`).

Step `type` is one of:

| Type    | Does |
|---------|------|
| `CALL`  | Runs an L1 operation (`op`) through `StepActivities`, stores the result under `as` (defaults to `op`). |
| `DECIDE`| Evaluates a SpEL expression (`expr`) over the current flow vars, stores it under `as`. |
| `PERSIST`/`NOTIFY` | Hands the current vars to a named code sink (`target`) — e.g. writing `PrecheckEntity`, or calling rupeek-web. |
| `WAIT`  | Parks the workflow (no cost while parked) until a SpEL condition (`until`) is true. |

Every step can carry a `when` guard (SpEL; step is skipped if false). `CALL`
steps carry `retries` (default 1 = no retry, parity with today's lender-core
behavior) and `timeoutSec` (default 30s), which become the activity's
`RetryOptions`/`ActivityOptions`.

An `Event` is its own steps list plus a `returns` list of variable names
handed back to whoever fired the event.

### Runtime (`FlowWorkflowImpl`)

One generic interpreter for every lender/flow — it knows step *types*, not
lenders. State is a single `vars` map, seeded from the `FlowRequest` at
start.

- `run(FlowRequest)` — the `@WorkflowMethod`. Runs the flow's top-level
  steps in order, then blocks (`Workflow.await(isEveryHandlerFinished)`)
  until all in-flight Updates finish, and returns the final vars.
- `event(name, payload)` — the `@UpdateMethod` used for both inbound
  callbacks and the status-pull endpoint. Events queue behind a `ready`
  flag so they can't run before the main path reaches its first `WAIT`
  (there's nothing to react against yet). A `WorkflowLock` serializes
  events against each other since they share `vars`. A special `$ready`
  event name is a no-op used purely to block a caller until the initial
  steps have reached `WAIT` (see below). Once `vars["done"]` is `true`,
  further events are no-ops — mirrors today's behavior of not
  re-evaluating a final precheck.
- `state()` — a `@QueryMethod` snapshot of `vars`, for introspection/debug.

### L1 stub (`L1`, `OperationDef`, `L1Registry`)

Deliberately thin — just enough surface for L2 to call something:

- `OperationDef`: `protocol` (REST now; SOAP/SFTP registered but
  unimplemented — `Adapters.defaults()` wires them to throw
  `UnsupportedOperationException` loudly rather than silently no-op),
  `method`, `url`, `query`, `headers`, a named `transformer` (request
  builder), and an `extract` map (output name → SpEL over the response).
- `L1.Callback`: how an inbound callback is routed — `event` to run,
  `correlateOn` (payload field identifying the customer), `okVar` (which
  event-return variable decides 200 vs 400), and an allow-list of `fields`
  passed into the event (everything else dropped).
- `L1Registry`: loads every `l1/*.json` and `flows/*.json` from the
  classpath once at startup. No Mongo-backed store yet — that's explicitly
  deferred to real L1 work.

### Activities (`StepActivities` / `StepActivitiesImpl`)

`call(lenderSlug, op, vars)` looks up the `OperationDef`, runs its
`transformer` to build a request, executes it through the protocol adapter,
then applies `extract`. A transport-level failure throws a plain
`RuntimeException` (retryable by Temporal); a config problem (missing op,
missing transformer, missing adapter) throws a non-retryable
`ApplicationFailure` tagged `ConfigError` — fails fast instead of retrying
something that will never succeed.

`sink(target, vars)` is the same pattern for `PERSIST`/`NOTIFY`.

### Expression evaluator (`Expr`)

One evaluator for all `DECIDE`/`when`/`until` expressions: SpEL via
`SimpleEvaluationContext` (no types, no constructors, no bean references —
config cannot reach arbitrary Java), with a `NullSafeMapAccessor` so missing
keys read as `null` instead of throwing, and a tiny fixed helper set
(`#f.allEqual`, `#f.anyEqual`, `#f.eqi` — case-insensitive trimmed
equality). Deterministic by construction (no clock, no randomness), so it's
safe to run inside workflow code.

### Protocol adapter (`RestAdapter`)

Real HTTP execution via `java.net.http.HttpClient`. `${NAME}` placeholders
in URL/headers/query resolve from system property then env var at call
time — secrets and env-specific values are never baked into config, only
their *names* are. Response status lands in the response map as `_status`
(4xx/5xx are just data; the flow/extract logic decides what they mean, not
the adapter).

### Temporal wiring (`TemporalConfig`)

- One task queue per lender: `"lender-{slug}"`.
- A single shared `WorkerFactory`/worker pool serves all lenders' queues in
  this process for now (one `Worker` per queue, same workflow+activity
  implementations registered on each).
- `WorkflowServiceStubs` target and namespace come from `LihProperties`
  (`localhost:7233` / `default` by default).

### HTTP surface (`ApiController` / `LihPrecheckService`)

Same three endpoints lender-core already exposes, now thin shells over an
L2 flow:

- `POST /api/v1/precheck` — starts the flow. Workflow id is
  `{lender}-precheck-{customerId}-{loanRequestId}`, started with
  `WorkflowIdReusePolicy.REJECT_DUPLICATE` (duplicate start = idempotent
  no-op, returns "already initiated"). After starting, it calls the
  `$ready` pseudo-event and blocks on it — this is what makes the endpoint
  synchronous like lender-core's today, by waiting for the flow's initial
  steps to reach `WAIT` (and surfacing a `CALL` failure as a 500, same as
  today) before responding.
- `GET /api/v1/precheck` — reads the stored `PrecheckEntity`; if it's still
  `PENDING`, first fires the `status` event synchronously to pull a fresh
  status before reading storage again.
- `POST /v1/callback` — looks up the callback route by `source_purpose` in
  the L1 stub, validates the correlation id (`SAFE_ID` regex — guards
  against injection into the visibility query below), then finds the
  target workflow with a Temporal visibility query:
  `WorkflowId STARTS_WITH '{lender}-precheck-{corr}-' AND ExecutionStatus =
  'Running'`. This is today's stand-in for a real `correlate` mechanism —
  see gaps below. The event's `okVar` result maps to HTTP 200/400.

## Decided in L2 design review

- **`project` is dropped from the L2/event vocabulary.** It was proposed in
  the flow-config audit to let a callback write a queryable projection
  (e.g. `PrecheckEntityTimble@1`) independent of the flow's own state. But
  the L1 worker/CEL design already models Mongo writes as a plain operation
  kind (`MONGO`) inside an L1 recipe — a projection write is just another
  recipe step, same as the lender HTTP call. Adding a parallel `project`
  concept at the L2 layer would duplicate that and give L2 a reason to know
  about lender-specific collections/fields, which is exactly what L2 is
  supposed to stay unaware of. An event now only ever does `{ "ref":
  "<L1 recipe>" }` — any Mongo write the callback needs happens inside that
  recipe.
- **`events{}` stays a flow-level map, not a field on `WAIT`.** The
  alternative (folding an event into the WAIT node it answers) works for a
  single-shot callback, but `axis-precheck.json`'s `status` event is
  caller-driven and repeatable — it doesn't correspond to one WAIT node at
  all. A flow-level map also matches reality better: a callback can arrive
  before the flow reaches its WAIT node, so the engine needs to buffer
  independent of node position regardless of how the config is shaped.

## Gap vs. the target L2 flow-config design

Design review settled on a richer L2 model than what's built here. These
are the concrete differences to resolve as part of "L2 improvement":

1. **No node/edge graph.** The target design is nodes (`TASK`/`WAIT`/
   `PARALLEL`/`JOIN`/`END`) connected by edges keyed on outcome labels — a
   case can branch to different next-steps based on what a task reported.
   What's built is a **linear ordered step list** with `when` guards for
   conditional skipping — there's no branching to a different node, only
   skip-or-run-next.
2. **`DECIDE` blurs the L1/L2 boundary.** The core invariant from design
   review is "L2 branches only on L1-produced outcome labels, never on raw
   lender data." `DECIDE` steps today evaluate SpEL directly over raw
   lender response fields (e.g. `event['name_match']`,
   `event['aadhar_match']` in `flows/axis-precheck.json`) — that
   classification logic belongs in L1's `outcome` mapping per the target
   design, not inline in the L2 flow.
3. **No `PARALLEL`/`JOIN`.** Not implemented at all; join semantics
   (all/any/N-of-M) are still an open design question from review, not
   just a missing feature.
4. **No version pinning.** The target design pins L1 config + the L2 graph
   per-case at start via a one-time manifest activity, so a running case is
   unaffected by a later config publish. Today, `L1Registry` loads
   everything once at process startup from the classpath — there's no
   per-case snapshot, no `(ref, version)` keying, nothing published at
   runtime.
5. **`respond`/`correlate` aren't generalized as config concepts yet**
   (`project` is intentionally not part of this list — see Decided above):
   - `respond` (map an event outcome to an HTTP status) is effectively
     already here as `okVar` → 200/400 — just not named/generalized that
     way.
   - `correlate` (extract the field that identifies which running case a
     callback belongs to) is **not** a config concept — it's hardcoded as
     the visibility-query workaround in `LihPrecheckService.callback`.
     Today it can only correlate on `customerId` (embedded in the workflow
     id), which doesn't disambiguate multiple concurrent cases for the same
     customer — see `decision-log.md`.
6. **Error handling is flatter than the target design.** Only a per-`CALL`
   `retries` count exists; there's no `onError` policy (retry-then-PARK vs.
   FAIL), no parked-case state, and no operator resume/skip/fail actions.
7. **Evaluator is SpEL, not CEL.** The design review (for L1, not L2)
   settled on CEL specifically for its safety properties over SpEL/Groovy/
   JEXL. `Expr`'s restricted `SimpleEvaluationContext` gets most of the way
   there for L2's own needs (no types/constructors/beans reachable), but
   this is worth revisiting once L1 work starts, so L1 and L2 don't end up
   with two different expression languages for no reason.
