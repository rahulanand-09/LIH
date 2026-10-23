# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

LIH (Lender Integration Hub) — a config-driven replacement for lendercore's hand-written per-lender Java. A
single recursive interpreter (`com.rupeek.flow`) runs a lender's precheck flow from published JSON config on
Temporal, so onboarding a lender or changing its API becomes a config change, not a redeploy.

`com.rupeek.l1` (the first-cut engine: `RECIPE`/operation-registry/CEL via `ConfigCompiler`+`Publisher`+
`GoldenRunner`) has been **deleted outright**. Everything described below is the current, only engine. Of the
docs under `docs/`, only **`docs/flow-lld.md`** describes current code — everything in `docs/history/` (`design.md`,
`hld.md`, `l2-lld.md`, `l1-*.md`, `temporal-operations.md`, `decision-log.md`) is design history for an L1/L2 split that
was scrapped; read them for "why," never for "how it works today."

## Commands

Requires JDK 21 (`pom.xml`'s `java.version`) — **this machine's default `JAVA_HOME`/`jenv` is JDK 11**, which
fails with `release version 21 not supported`. Use a real JDK 21 explicitly, e.g. Homebrew's:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"
```

No `mvnw` — use the system `mvn` with the env above set.

```bash
mvn test                                    # full suite (needs Docker: Testcontainers spins up its own Mongo)
mvn test -Dtest=ClassName                   # one test class
mvn test -Dtest=ClassName#methodName        # one test method
mvn compile / mvn test-compile              # fast compile-only checks
```

**Local dependencies**, already running on this dev machine (`docker-compose.yml` defines the same two images for
a fresh one): `lih-mongo` on `27018` (registry + `lendercore` business DB), a local `redis` on `6379`, and a
Temporal dev server via `temporal server start-dev --port 7233 --ui-port 8233` (UI at `localhost:8233`). Tests
that need Mongo use **Testcontainers** (`FlowMongoTestSupport`, a separate ephemeral instance), not `lih-mongo`.

**Running the real app** (`com.rupeek.LihWorkerApplication`, port 8091, polls Temporal task queue `lih-l2`):
publishes nothing itself — the registry (`lih_flow_config`) must already be populated. It also needs live HTTP
backends for the `core`/`axis-precheck`/`rupeekweb` resources (`src/main/resources/flow-config/resources/*.json`);
locally that means a WireMock instance on the port those resources' `*_BASEURL` env vars point at (see
`FlowWorkerDaemon`'s `stubLender` for the exact stub routes/fixtures under `src/test/resources/axis-fixtures/`).

**Package layout** (`com.rupeek.flow`): `model` (FlowDef/TaskDef/nodes), `engine` (`FlowWorkflow(Impl)`,
`FlowState`, `InputResolver`, `ResumeAction`), `task` (`TaskActivity(Impl)`, request/result, `ErrorClassifier`;
executors in `task.executor.{http,mongo,map,gen}`), `registry` (Registry/Publisher/Loader/Source classes; Temporal
registry activities `Fetch*/Resolve*` in `registry.activity`; storage in `registry.store`), `config`
(`FlowAutoConfiguration`, `FlowRegistryAutoConfiguration`, `FlowProperties`), `worker` (`FlowTemporalWorker`),
`expr`, `audit`, `resource`; `api` and `observability` are empty placeholders (`package-info.java` only).

**Config resources** are unified under `src/main/resources/flow-config/{flows,tasks,resources}`.

**Dev tools** (`src/dev/java/`, added to the test classpath by `build-helper-maven-plugin`'s `add-test-source`, so
`mvn test-compile` builds them; package names are unchanged, e.g. `com.rupeek.flow.dev`. They use test helpers from
`src/test` such as `TestNativeExecutors`/`FlowMongoTestSupport`. Hand-wired `main()`s, no Spring context — build a classpath
file once with `mvn dependency:build-classpath -Dmdep.outputFile=/tmp/lih-cp.txt`, then run with
`java -cp "target/classes:target/test-classes:$(cat /tmp/lih-cp.txt)" <fully.qualified.ClassName>`):
- `FlowWorkerDaemon` — standalone worker + its own WireMock + bootstraps the registry (idempotent) against
  `lih-mongo`, on queue `lih-l1`. The closest thing to a one-process local sandbox.
- `FlowTriggerClient <loanRequestId> [queue]` — starts one `axis-precheck-decomposed` case (queue defaults to
  `lih-l1`; pass `lih-l2` to hit the real `LihWorkerApplication` instead).
- `RegistryDataMigration` (`src/dev/java`, `com.rupeek.flow.registry.store` package, needs its package-private `BsonSpecCodec`) —
  one-off registry data-surgery scripts live here; check it before assuming it's still needed, it's meant to be
  disposable.

## Architecture

### The engine is one recursive interpreter, not a fixed layer stack

`FlowWorkflowImpl` (a single Temporal workflow type) walks a `FlowDef` graph of `Node`s: `TaskNode` (one native
Activity call), `SubflowNode` (another `FlowDef`, run as a real Temporal **child workflow**, or — if
`inline=true` — spliced into the *same* workflow execution via a recursive `runFlowBody` call, no child workflow
history at all), plus `ParallelNode`/`JoinNode`/`WaitNode`/`EndNode`. A `SubflowNode` can point to a `FlowDef`
containing more `SubflowNode`s to arbitrary depth (`MAX_DEPTH=25` is a cycle backstop, not a designed tier count)
— **there is no fixed "L1/L2" or numbered-level concept anywhere in this code**, and nothing should reintroduce
one (nesting depth is a property of who calls a flow, not of the flow itself).

`PARK` (a node that exhausts its retries) is a suspend via `Workflow.await`, not a thrown failure — it survives
the parent retrying/completing and is resumed by an operator signal straight to the workflow's own ID.

### Two registries, same storage shape, resolved separately

- **Flow registry**: `FlowDef` (`taskRef`/`flowRef` + graph shape, no business config, no CEL) — `FlowPublisher`,
  `FetchFlowActivity`/`ResolveFlowActivity`.
- **Task registry**: `TaskDef` (`taskRef`+`kind`+`fields` — a leaf node's actual HTTP/MONGO/MAP/GEN config,
  including every CEL expression) — `TaskPublisher`, `FetchTaskActivity`/`ResolveTaskActivity`. A `TaskNode`'s
  `kind` lives on the graph node (visible without a fetch); `TaskDef.kind` duplicates it so `TaskPublisher` can
  validate a `TaskDef` on its own, without needing the referencing graph.

Both resolve a pinned/latest version **once, at flow start** (`FlowWorkflowImpl.resolveVersions`), via a
Mongo-backed `ConfigStore` (collections `flow_version`/`task_version`, unique on `(ref, version)`, plus one
shared `flow_pointer` keyed `TYPE:ref`). Versions are immutable — `insert` throws on a duplicate `ref@version`.
`RedisConfigStore` is an optional read-through cache in front of it (`flow.registry.cache.enabled`): versions
cached long (immutable, never stale), the `latest` pointer cached briefly and evicted the instant it moves.

**Storage has two implementations of the same `ConfigStore` interface**: `MongoConfigStore` (raw
`com.mongodb.client` driver calls) for the non-Spring `main()` dev tools, and `SpringDataConfigStore`
(`FlowVersionRepository`/`TaskVersionRepository`/`FlowPointerRepository`) for the real app. **The Mongo-repository
wiring is its own top-level `FlowRegistryAutoConfiguration`, not nested inside `FlowAutoConfiguration`** —
nesting it broke app startup (`@EnableMongoRepositories` registers bean definitions before `MongoAutoConfiguration`'s
`MongoClient` bean exists at that nesting depth; a standalone `@AutoConfiguration` with explicit
`after`/`afterName` gets real two-phase ordering). Each version row carries `spec`/`specHash` (the authoritative
JSON string + its hash) **and** `specView` (the same content as a real, browsable BSON subdocument) — every
`$`/`.` in a key is escaped to a fullwidth lookalike (`BsonSpecCodec`) first, because CEL's reserved
`$each`/`$let` template keys and `MongoQueryBinder`'s dotted update paths (e.g. `"responseMap.pan"`) are both
real, in-use features that Mongo's own field-name rules would otherwise reject on insert.

### CEL: where it's validated and where it runs

CEL (`dev.cel:cel`, via `com.rupeek.flow.expr`) lives entirely inside a `TaskDef.fields` — never in a `FlowDef`
(which only has JSONPath-style `in`-wiring, e.g. `"$.in.loanRequestId"`). `ScopeDecl` declares which variables an
expression may reference (`OPERATION` for request templates, `HTTP_RESPONSE` for `success`/`classify`/`out`,
`RESULT` for MONGO/GEN `out`); `TemplateEvaluator` compiles a whole template tree (handling `=`-prefixed CEL
strings, `==`-literal escapes, and `$each`/`$let` iteration) in one pass.

Each native executor (`Http`/`Mongo`/`Map`/`GenTaskExecutor`) has a no-I/O `validate(fields)` that compiles every
CEL-bearing field without evaluating it — `execute()` calls it first (one source of truth), and
`TaskPublisher.publish()` calls it too, dispatching on `TaskDef.kind()`, **before** anything is written to the
store. This is deliberate: a broken CEL expression must be rejected at publish time, not discovered the first
time a live case hits it. `FlowPublisher` does *not* do this (nothing to compile in a `FlowDef`) — don't copy its
"nothing to validate" reasoning onto anything that touches `TaskDef`.

### Executors are config-interpreting, not business-logic Java

`OperationKind` is `HTTP`/`SOAP`/`SFTP`/`MONGO`/`MAP`/`GEN`/`CUSTOM`; only the first four native kinds are
implemented (`TaskActivityImpl` dispatches by kind). A MONGO node's `filter`/`set`/etc. use `MongoQueryBinder`'s
`:name` placeholder scheme — **not CEL** — only its `out` is CEL. A MAP node's rendered template root map *is*
its outputs directly, with one reserved `outcome` key popped off to become the node's outcome label (how a pure
CEL guard becomes a node with no I/O at all).

### Audit and PII

`TaskAuditor`/`MongoAuditWriter` write to lendercore's existing `LenderApiAuditLog` collection (fire-and-forget,
drops the oldest queued record under backpressure rather than blocking a lender call) — a node is audited only if
its `fields.audit`/`requestType` resolves to non-null. PII masking (`PiiMasker`) is scoped to each node's own
declared `piiPaths`, not a blanket field-name scan — a prior blanket-masking version corrupted Axis's unrelated
`pan` match-status flag, which is why it's narrow now.
