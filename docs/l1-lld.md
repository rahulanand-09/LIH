# L1 Worker — Low-Level Design

Status: draft for implementation, 2026-10-17. Package: `com.rupeek.l1`.
Decisions and open items are tracked in [`l1-decision-log.md`](l1-decision-log.md).

L1 runs **one lender step** (e.g. Axis Dedupe) from published config and
returns **one outcome**. It is one generic Temporal activity, a generic
engine, a fixed set of operation kinds, and CEL for every expression. There
is **no lender-specific Java** in L1. The proof of concept is Axis precheck,
starting with Axis Dedupe.

> Source of truth: this document reflects the L1 design agreed so far. It is
> written independently of the current L2 code in `com.rupeek.lih`; the
> touchpoints to reconcile later are listed in
> [§17](#17-reconciliation-with-l2-later).

---

## 1. Scope

**In the POC**

1. One generic activity, `RunTask`, that executes a published recipe and
   returns an outcome or a typed failure.
2. Operation kinds `HTTP` (REST), `MONGO`, `MAP`, `GEN`.
3. CEL as the only expression language, compiled and checked at publish.
4. A versioned config registry in Mongo; versions immutable, pinned per case.
5. Audit, tracing, metrics and PII masking around every operation, automatically.
6. Axis precheck recipes: Dedupe, Timble, Timble-callback mapping, status,
   record result, rupeek-web notify.
7. Golden, rerun and parity tests in CI.

**Not in the POC**

- L2 flow design (only the contract with L2 is fixed here).
- Config authoring UI (config is edited in git).
- Operation kinds `AUTH`, `CRYPTO`, `FILE`, `PUBLISH`, `RENDER`, `LOCK`, SOAP.
- Lenders other than Axis.
- Traffic routing between lendercore and LIH.
- Encrypting PII inside Temporal history (avoided: customer data stays inside the worker).

---

## 2. Concepts

| Concept | What it is | Code or config |
|---|---|---|
| **Recipe** | One lender step: ordered steps, guards, early returns, declared outcomes and outputs | Config, versioned |
| **Operation definition** | How one operation runs: kind + request / response / error rules. Reused by many recipes | Config, versioned |
| **Resource** | Where and with which credentials: base URL per env, auth, secret refs, timeouts, collections | Config, read live |
| **Operation kind** | A generic Java executor for one type of work (`HTTP`, `MONGO`, `MAP`, `GEN`, …) | Platform code |
| **Engine** | Runs a recipe: steps, CEL, failure classes, limits | Platform code |
| **CEL** | Expression language inside config | Library `dev.cel:cel` |
| **`rk.*` functions** | Shared Java helpers CEL lacks (money, dates, group-by, …) | Platform code |
| **Interceptors** | Audit, masking, metrics, tracing around every operation | Platform code |
| **Registry** | Stores and serves config versions; publish-time validation | Platform code + Mongo |

Rules that hold everywhere:

1. **One activity, at most one lender side effect.** Two lender actions = two recipes.
2. **Recipes are linear.** Guards, early return, error mapping only. Waiting, loops over lender calls and choosing between lender calls belong to L2.
3. **Every recipe is safe to rerun.** Check what exists, reuse stored ids, upsert, save intent before calling the lender.
4. **A timed-out non-idempotent call is `UNKNOWN`,** never retried blindly.
5. **No lender names in Java.** Lender differences live only in config.
6. **Config is code:** git → PR review → CI validation + golden tests → publish. Published versions never change.
7. **Secrets and environment values live in resources;** recipes and operations only reference them.
8. **No PII leaves the worker.** Outputs to L2 are ids and statuses; audit and logs are masked.
9. **CEL sees only plain data** (maps, lists, strings, numbers, booleans, null, timestamps). No Java objects, no reflection, limits always on.
10. **No large binaries through Temporal or step outputs** — pass storage references.
11. **Respect legacy records:** keep lendercore's fields, `_class` and `version` intact when writing shared Mongo documents.
12. **Count `CODE` operations;** a rising count means the platform is missing a kind or function.

---

## 3. Tech stack

Java 21 and Spring Boot 3.x (decided 2026-10-17). The repo `pom.xml` was upgraded on 2026-10-17.

| Area | Choice |
|---|---|
| Language / framework | Java 21, Spring Boot 3.5.16 (latest 3.x; Boot 4 skipped: Spring 7 + Jackson 3); Jakarta EE namespace (`jakarta.*`) |
| Workflow engine | Temporal Java SDK 1.40.0 |
| Expressions | `dev.cel:cel` 0.14.0, behind our `ExpressionEngine` interface |
| HTTP | JDK `java.net.http.HttpClient`, one client per resource, used directly so the executor can tell "not sent" (connect timeout) from "sent, no reply" (request timeout). Spring `RestClient` over `JdkClientHttpRequestFactory` is an option if it keeps that distinction |
| Mongo | Spring Data MongoDB (`MongoTemplate`, no repositories/entities for business data) |
| Caches | Caffeine |
| JSON | Jackson; JSON Schema validation for config documents (library to pick in M1) |
| Secrets | Environment variables for the POC, behind `SecretResolver` |
| Observability | Micrometer (Prometheus), Micrometer Tracing with the OpenTelemetry bridge, SLF4J + MDC |
| Concurrency | Java 21 virtual threads: evaluate for activity execution and blocking HTTP/Mongo calls in M3 (Temporal worker and Spring Boot options to confirm) |
| Tests | JUnit 5, WireMock, Testcontainers (Mongo), Temporal `TestActivityEnvironment` / `TestWorkflowEnvironment` |

Dependencies to add in M1: `dev.cel:cel`, `com.github.ben-manes.caffeine:caffeine`, a JSON Schema validator, WireMock and Testcontainers (test scope).

---

## 4. Package layout

All new code lives under `com.rupeek.l1`. No class names a lender.

| Package | Key classes | Responsibility |
|---|---|---|
| `l1.api` | `TaskActivity`, `TaskRequest`, `TaskResult`, `StepTrace`, `L1FailureType`, `FailureDetails` | Contract with L2 |
| `l1.model` | `Recipe`, `Step`, `ReturnSpec`, `OnError`, `OperationDef`, `HttpSpec`, `MongoSpec`, `MapSpec`, `GenSpec`, `Resource`, `ResourceEnv`, `AuthSpec` | Immutable config objects parsed from JSON |
| `l1.expr` | `ExpressionEngine`, `CelExpressionEngine`, `CompiledExpr`, `ScopeDecl`, `TemplateEvaluator`, `ExprCompileException`, `ExprEvalException` | Compile / evaluate CEL; render templates |
| `l1.expr.fn` | `FunctionLibrary`, `RkFunctions` | `rk.*` functions registered with CEL |
| `l1.engine` | `RecipeRunner`, `CompiledRecipe`, `CompiledOperation`, `ConfigCompiler`, `OperationInvoker`, `StepContext`, `ErrorClassifier`, `L1Failure`, `OutputGuard`, and the interfaces the engine needs: `RecipeSource`, `ResourceProvider` | Compile and run a recipe; classify and route failures |
| `l1.ops` | `OperationExecutor`, `PreparedOperation`, `OperationRegistry`, `ExecContext`, `CallRecorder`, `ErrorClass`, `TransportException`, `OperationException` | Interface every kind implements |
| `l1.ops.http` | `HttpOperationExecutor`, `HttpClientFactory`, `AuthProvider`, `ApiKeyHeaderAuth`, `StaticBearerAuth`, `NoAuth` | REST |
| `l1.ops.mongo` | `MongoOperationExecutor`, `MongoQueryBinder` | Fixed-shape Mongo operations |
| `l1.ops.map` | `MapOperationExecutor` | Templates |
| `l1.ops.gen` | `GenOperationExecutor` | Stable ids, time |
| `l1.intercept` | `OperationInterceptor`, `InterceptorChain`, `TracingInterceptor`, `MetricsInterceptor`, `AuditInterceptor`, `AuditWriter`, `PiiMasker` | Cross-cutting concerns |
| `l1.registry` | `ConfigStore` (`MongoConfigStore`, `InMemoryConfigStore`), `StoreRecipeSource`, `StoreResourceProvider`, `RegistryService`, `ConfigCodec`; classpath mode: `ClasspathConfigLoader`, `StaticRecipeSource`, `InMemoryResourceProvider` | Store, load, cache and serve config versions and resources |
| `l1.publish` | `Publisher`, `ConfigBundle`, `SchemaValidator`, `GoldenRunner`, `GoldenCase`, `PublishReport`; `cli.L1ConfigCli` | Validate (schema, versions, compile, golden) and store new versions; CLI for CI |
| `l1.secrets` | `SecretResolver`, `EnvSecretResolver` | `secretRef` → value at call time |
| `l1.worker` | `TaskActivityImpl`, `L1Components` (the single wiring path), `L1AutoConfiguration`, `L1Properties`, `L1TemporalWorker` | Temporal activity + worker start-up |
| `l1.dev` | `L1ProbeWorkflow` | Dev-only workflow that calls `RunTask` without L2 (`l1.dev.probe-workflow=true`) |

Dependency direction: `worker → {engine, registry}`; `registry → engine` (implements `RecipeSource`, `ResourceProvider`); `engine → {expr, ops, intercept, model}`; `ops`/`intercept` → `model`/`expr`; `api` depends on nothing. No cycles.

---

## 5. Contract with L2

L2 sees one activity type, `RunTask`, for every lender and step.

```java
@ActivityInterface
public interface TaskActivity {
    @ActivityMethod(name = "RunTask")
    TaskResult runTask(TaskRequest request);
}

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskRequest(
        String recipeRef,            // "axis.precheck.dedupe"
        int recipeVersion,           // pinned by L2 at case start
        String caseId,               // Temporal workflow id
        int runNo,                   // increments only on revert
        Map<String, Object> inputs   // ids and event payloads only; no PII by design
) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskResult(
        String outcome,              // one of the recipe's declared outcomes
        Map<String, Object> outputs, // declared, non-PII, <= 16 KB
        String recipeRef,
        int recipeVersion,
        List<StepTrace> steps        // stepId, opRef, status, durationMs
) {}

public record StepTrace(String stepId, String opRef, String status, long durationMs) {}
```

**Callbacks** use the same activity: L2 calls `RunTask` with a mapping recipe
(e.g. `axis.precheck.timbleCallback`) and the payload in `inputs.event`; the
recipe turns it into an outcome.

### Failures

Every failure leaves the activity as a Temporal `ApplicationFailure` with
one of four types and a `FailureDetails` payload
(`recipeRef`, `recipeVersion`, `stepId`, `opRef`, `errorClass`, `httpStatus`, masked `message`).

| Failure type | Retryable? | Raised when | L2 should |
|---|---|---|---|
| `L1_TECHNICAL` | Yes | Network error, 5xx/429 on idempotent calls, timeout before send, transient Mongo error | Let Temporal retry; park when exhausted |
| `L1_BUSINESS` | No | Lender said no and the recipe raises instead of returning an outcome | Route or park |
| `L1_UNKNOWN` | No | Non-idempotent call timed out / 5xx after sending | Park, or run a reconcile step |
| `L1_PERMANENT` | No | Config/data error: CEL error, missing required output, input mismatch, unknown op | Park for ops |

### Activity options (L2 sets; L1 proposes defaults)

| Option | Default | Why |
|---|---|---|
| Start-to-close | Recipe `timeoutBudgetMs` (computed at publish = sum of op timeouts + 20% margin) | Never cut a recipe mid-step |
| Heartbeat timeout | 30 s | Worker heartbeats after every step |
| Retry | initial 2 s, backoff 2.0, max interval 60 s, max 5 attempts | Tune in POC |
| Do not retry | `L1_BUSINESS`, `L1_UNKNOWN`, `L1_PERMANENT` | Only technical failures are retried |

Task queue: `lih-l1` (single queue for the POC; per-lender queues later if one lender starts delaying others).

---

## 6. Config model

Three document types. JSON. Expressions are CEL strings.

### 6.1 Conventions

- In a **recipe**, `when`, `in`, `out` and `auditKeys` values are CEL. `'axis'` (single quotes) is a CEL string literal.
- In **operation templates** (HTTP `request`, MAP `template`), a string starting with `=` is CEL; any other string is a literal; a literal starting with `=` is written `==`.
- **Mongo** `filter`/`set`/`setOnInsert`/`inc` are fixed JSON. Values written `":name"` bind from `params` or `sys`. Field names and operators are never dynamic.
- Secrets appear only as `secretRef`.
- Operation references are explicit: `op@version`.

### 6.2 Recipe

| Field | Type | Meaning |
|---|---|---|
| `recipe` | string | `<lender>.<process>.<step>`, e.g. `axis.precheck.dedupe` |
| `version` | int | Declared in git (missing = 1); raised for every change; immutable once published |
| `lender` | string | For audit/metrics only, never for branching |
| `resources` | map alias → resource id | Gives recipe-level expressions `env.<alias>.<key>` (optional) |
| `inputs` | map name → type | Required shape of `TaskRequest.inputs` |
| `outputs` | map name → type | What may go back to L2; must be non-PII |
| `outcomes` | list | Every outcome the recipe can return |
| `auditKeys` | map key → CEL | `loanRequestId`, `customerId` for audit rows |
| `steps` | list of Step | In order |
| `return` | `{outcome, out}` | Result after the last step |

Types: `string`, `int`, `double`, `bool`, `map`, `list`, `timestamp`, `dyn`.

### 6.3 Step

| Field | Type | Meaning |
|---|---|---|
| `id` | string | Unique; later steps read `steps.<id>.<output>` |
| `op` | `ref@version` | Operation to run (absent on a return step) |
| `when` | CEL bool | Skip when false |
| `in` | map param → CEL | Operation parameters |
| `return` | `{outcome, out}` | Early-return step |
| `onError` | map class → `raise` \| `return:<OUTCOME>` | Default `raise` for every class |

### 6.4 Operation definition — common fields

| Field | Type | Meaning |
|---|---|---|
| `op`, `version` | string, int | Reference; version declared in git as for recipes |
| `kind` | `HTTP` \| `MONGO` \| `MAP` \| `GEN` | Selects the executor |
| `resource` | resource id | Required for `HTTP`, `MONGO`; optional for `MAP` (to read `env`) |
| `params` | map name → `{type, pii}` | Inputs; `pii: true` = mask + output-guard |
| `out` | map name → CEL | Outputs from the result scope |
| `required` | list | Outputs that must be non-null, else `PERMANENT` |
| `timeoutMs` | int | Overrides the resource default |
| `idempotent` | bool, default false | Lender dedupes on a key we send; affects classification |
| `audit` | string or `=CEL` | Audit `requestType`; no row when absent |
| `piiPaths` | list of JSON paths | Request/response fields to mask |

### 6.5 Resource

```json
{
  "resource": "axis-precheck",
  "kind": "HTTP",
  "auth": { "type": "API_KEY_HEADER", "header": "API-key", "secretRef": "AXIS_PRECHECK_API_KEY" },
  "headers": { "Content-Type": "application/json" },
  "timeoutMs": { "connect": 120000, "read": 120000 },
  "pool": { "maxConnections": 20 },
  "env": {
    "qa":   { "baseUrlRef": "AXIS_PRECHECK_BASEURL", "values": { "preCheckAgreementId": "2181" } },
    "prod": { "baseUrlRef": "AXIS_PRECHECK_BASEURL", "values": { "preCheckAgreementId": "323" } }
  }
}
```

| Field | Meaning |
|---|---|
| `kind` | `HTTP` or `MONGO` |
| `auth.type` | POC: `NONE`, `API_KEY_HEADER`, `STATIC_BEARER` |
| `env.<name>.baseUrl` / `baseUrlRef` | Literal URL, or env-var name holding it |
| `env.<name>.values` | Non-secret values; operations on this resource read them as `env.<key>` |
| `datasourceRef`, `database`, `collections` | `MONGO` only; `collections` is an allowlist |

The active environment name comes from the worker's configuration (`l1.env`).

### 6.6 Git layout (config repo or `src/main/resources/l1-config` for the POC)

```text
l1-config/
  resources/    axis-precheck.json  core.json  lendercore-mongo.json  rupeekweb.json
  operations/   common/core.fetchCustomerByLoanRequest.json
                common/precheck.findByLoanRequest.json  common/precheck.upsertInitiated.json
                common/precheck.markDedupeDone.json
                axis/axis.precheck.invoke.json  axis/map.axis.precheckRequest.json
  recipes/      axis/axis.precheck.dedupe.json
  tests/        axis/axis.precheck.dedupe/*.golden.json
```

JSON Schemas live with the code that reads the config, in `src/main/resources/l1-schemas/`
(`recipe`, `operation`, `resource`, `golden`). The authoring and publishing guide is
[`l1-config-publishing.md`](l1-config-publishing.md).

---

## 7. Expression layer

```java
public interface ExpressionEngine {
    CompiledExpr compile(String source, ScopeDecl scope);       // ExprCompileException
    Object eval(CompiledExpr expr, Map<String, Object> vars);    // ExprEvalException -> PERMANENT
}
public record ScopeDecl(Map<String, String> variables) {}        // name -> CEL type
public record CompiledExpr(String source, String configPath, Object program) {}
```

`CelExpressionEngine` builds one compiler and one runtime at start-up:
planner runtime, standard macros, extensions strings, regex, lists, sets,
math, encoders, bindings. Optional syntax on (`x.?f.orValue(d)`);
heterogeneous numeric comparisons on. Exact builder/extension class names
are confirmed in the M1 spike.

### Scopes

| Where | Variables |
|---|---|
| Step `when`, `in`, `return.out`, recipe `auditKeys` | `input`, `steps`, `env` (by alias), `sys` |
| HTTP `request`, MAP `template`, MONGO `:param` values | `params`, `env` (the op's resource), `sys` |
| HTTP `success`, `classify`, `out` | `params`, `resp` (`status`, `headers`, `body`), `env` |
| MONGO / MAP / GEN `out` | `params`, `result` |

`sys`: `sys.now` (one timestamp per attempt), `sys.caseId`, `sys.runNo`.

CEL behaviours confirmed against `dev.cel` 0.14.0 (see `CelExpressionEngineTest`):

- Inputs are normalised before evaluation: Java `null` → CEL null (CEL throws on raw Java nulls), `Integer` → `Long`, `Float`/`BigDecimal` → `Double`; any non-plain Java object is rejected.
- `x.?f.orValue(d)` returns `d` only when key `f` is **missing**. A key present with value `null` returns `null`, so null-guards compare against `null` explicitly.
- Reading a missing key with plain `x.f` fails (→ `PERMANENT`); a map literal lookup with an unknown key fails the same way.
- Results: CEL null → Java `null`; maps and lists come back as mutable Java collections; ints as `Long`.

### Templates (`TemplateEvaluator`)

- `"=expr"` → CEL result, type preserved; `"==x"` → literal `"=x"`; other strings literal.
- `{"$each": "<CEL list>", "as": "x", "$let": {"k": "<CEL>"}, "template": {...}}` → renders `template` per item with `x` and `$let` values in scope (`$let` evaluated in order, each may use earlier ones).
- `null` results stay JSON `null`. Any failure → `PERMANENT` with the config path.

### Function library

```java
public interface FunctionLibrary {
    String version();                        // e.g. "1"
    List<CelFunctionDecl> declarations();
    List<CelFunctionBinding> bindings();
}
```

Functions are only added or fixed, never changed in meaning, so a recipe
published against library version *n* runs on any later version.

POC set:

| Function | Purpose |
|---|---|
| `rk.round(x, scale, mode)` | `BigDecimal.valueOf(x).setScale(scale, mode)` → exact parity with legacy (`HALF_UP`, `HALF_DOWN`, `DOWN`, …) |
| `rk.sum(list)`, `rk.sum(list, 'field')` | Totals (CEL has no reduce) |
| `rk.groupSum(list, 'key', 'value')`, `rk.groupCount(list, 'key')` | `groupingBy` equivalents |
| `rk.lookup(table, key, default)` | Master-data tables from resources |
| `rk.json(x)`, `rk.parseJson(s)` | JSON ⇄ string |
| `rk.date.parse(s, fmt)`, `rk.date.format(v, fmt)` | Dates |
| `rk.mask(s, keepLast)` | Masking in messages |
| `rk.nameSplit(customer, 'first'\|'middle'\|'last')` | Legacy name rule (1 part → last `"."`; ≥4 → last = rest joined; blank proof name → raw first/last) |
| `rk.uuidFrom(seed)` | Name-based UUID v5 |

Later: `rk.dec.add/mul/div`, `rk.date.addDays`, `rk.hash`, `rk.pad`, `rk.phone`.

### Limits (via `CelOptions`)

| Limit | Value |
|---|---|
| `maxExpressionCodePointSize` | 2000 |
| `maxParseRecursionDepth` | 32 |
| `comprehensionMaxIterations` | 1000 |
| `maxRegexProgramSize` | library default (revisit in M1) |

Type-checking catches unknown variables/functions and scalar type errors at
publish. Lender responses are `dyn`, so misspelt response fields are caught
only by golden tests. CEL native types stay **off**.

---

## 8. What config can and can't express (tier rule)

Each piece of logic goes to the first tier that keeps it readable:

| Tier | Where | Typical logic |
|---|---|---|
| 1 | Plain CEL (≈ 3 lines) | Mapping, null checks, conditions, status maps, string/regex, list `filter`/`map`/`exists`/`sort` |
| 2 | `rk.*` function (generic, unit-tested, no I/O) | Money rounding, dates, sums/group-by, lookups |
| 3 | `CODE` operation (versioned Java named by capability) | IRR solvers, slot-packing rules, loops that call Core/DB per pass |
| 4 | Operation kind | Anything needing keys, files or I/O (crypto, SFTP, PDF) |

Health signals: `CODE` share of steps (< 10%), longest CEL expression (capped
at publish), golden coverage per recipe (100%).

---

## 9. Engine runtime

```java
public TaskResult run(TaskRequest req) {
    CompiledRecipe r = registry.recipe(req.recipeRef(), req.recipeVersion());
    StepContext ctx = StepContext.start(req, r.validateInputs(req.inputs()),   // PERMANENT on mismatch
                                        resources.envFor(r), sys(req));
    for (CompiledStep s : r.steps()) {
        heartbeat(s.id());                                   // also surfaces cancellation
        if (s.when() != null && !ctx.evalBool(s.when())) { ctx.skip(s); continue; }
        if (s.isReturn()) return ctx.finish(s.returnSpec());
        CompiledOperation op = registry.operation(s.opRef());
        Map<String, Object> params = ctx.evalMap(s.in());     // PERMANENT on error
        try {
            ctx.record(s, invoker.invoke(op, params, ctx));
        } catch (L1Failure f) {
            OnError a = s.onError().forClass(f.errorClass());
            if (a.isReturn()) return ctx.finish(a.outcome(), f);
            throw f.toApplicationFailure();
        }
    }
    return ctx.finish(r.returnSpec());
}
```

`OperationInvoker.invoke(op, params, ctx)`:

1. Run `InterceptorChain` around `executor.execute(op, params, execCtx)`; the executor returns a scope (`resp` or `result`) or throws `TransportException(sent: boolean, timeout: boolean)`.
2. If `success` is defined and false → `classify` (or default rules) → throw `L1Failure`.
3. Evaluate `out`; check `required`; return outputs.

`StepContext` (memory only): `input`, `steps`, `env`, `sys`, `List<StepTrace>`.
Only `TaskResult` reaches Temporal history.

### Default classification (`ErrorClassifier`)

| Situation | Class |
|---|---|
| Not sent: connection refused, DNS, connect / pool timeout | `TECHNICAL` |
| Read timeout on GET or `idempotent: true` | `TECHNICAL` |
| Read timeout on POST/PUT/PATCH/DELETE, not idempotent | `UNKNOWN` |
| HTTP 429, 502, 503, 504 | `TECHNICAL` |
| HTTP 500, idempotent | `TECHNICAL` |
| HTTP 500, not idempotent | `UNKNOWN` |
| Other 4xx, or 2xx with `success` false | `BUSINESS` (unless `classify` says otherwise) |
| Body not parseable | `PERMANENT` |
| Mongo network / timeout / step-down | `TECHNICAL` |
| Mongo duplicate key on insert, `expect` violated | `PERMANENT` |
| CEL eval error, missing required output, input mismatch, unknown op | `PERMANENT` |

`classify` (CEL, returns a class name) overrides HTTP rows only when a response exists.

### Output guard (`OutputGuard`)

`ctx.finish` checks outputs against declared names/types, a 16 KB limit, and
that no value of a `pii: true` parameter seen in the run appears in the
outputs. Violation → `PERMANENT`.

### Unexpected errors

An exception that is not an `L1Failure` (a bug) leaves `RunTask` as non-retryable `L1_PERMANENT` with the message
`internal error: <ExceptionType>`: a bug must never cause a blind retry of a lender call.

### Rerun safety

No step journal in the POC. Recipes are written rerun-safe (rule 3). A
journal can be added later if a recipe cannot be made rerun-safe.

---

## 10. Operation kinds

```java
public interface OperationExecutor {
    String kind();
    void validate(OperationDef def, ValidationContext vc);                   // publish time
    Map<String, Object> execute(OperationDef def, Map<String, Object> params,
                                ExecContext ctx) throws TransportException;  // {resp} or {result}
}
```

### HTTP

| Concern | Rule |
|---|---|
| URL | `resource baseUrl` + `request.path`; path validated at publish (no scheme/host/`..`); CEL path segments URL-encoded |
| Query / headers | Templates; header names literal |
| Body | `request.body` template; `bodyEncoding` `JSON` (default) or `FORM` |
| Auth | `AuthProvider` by `resource.auth.type`; secret from `SecretResolver` at call time; never logged |
| Timeouts / pool | Resource defaults, op override; one `HttpClient` per resource. The JDK client has no per-client connection cap, so `pool.maxConnections` is not enforced yet (JVM-wide `jdk.httpclient.connectionPoolSize` applies) |
| Response | `resp.status`, `resp.headers`, `resp.body` (JSON → maps/lists; non-JSON → string) |
| Not done here | Retries (Temporal), success checks (engine) |

HTTP spec fields: `request{method, path, query, headers, body, bodyEncoding}`, `success`, `classify`.

### MONGO

| Concern | Rule |
|---|---|
| Actions | `FIND_ONE`, `FIND_MANY` (`limit` ≤ 100), `INSERT` (inserts the bound `set` document), `UPDATE_ONE`, `UPSERT` (`setOnInsert` only here) |
| Shape | `collection`, `filter`, `set`, `setOnInsert`, `inc`, `sort`, `projection` fixed; `":name"` bound from `params`/`sys` |
| Rejected at publish | `$where`, `$function`, `$accumulator`, `$expr`; collections outside the resource allowlist |
| `expect` | e.g. `{matched: 1}`; violation `PERMANENT` |
| Legacy docs | Updates add `$inc: {version: 1}` and set `last_updated`; inserts set `_class` when configured |
| Scope | Reads: `result` = doc / list / null. Writes: `result = {matched, modified, upsertedId}` |

### MAP

Renders `template` via `TemplateEvaluator`; `result` = rendered value;
default output `value`.

### GEN

| Action | Behaviour |
|---|---|
| `STABLE_ID` | `params.reuse` if non-empty, else UUID v5 of `params.seed` |
| `NOW` | `sys.now` |

---

## 11. Interceptors and observability

Order (outermost first): `Tracing → Metrics → Audit (with PiiMasker) → executor`.
An interceptor failure never fails the step.

```java
public interface OperationInterceptor {
    Map<String, Object> around(OperationCall call, Proceed next) throws TransportException;
}
```

### Audit → `LenderApiAuditLog` (async, bounded queue)

| Field | From |
|---|---|
| `lenderSlug` | `recipe.lender` |
| `loanRequestId`, `customerId` | `recipe.auditKeys` |
| `requestType` | `op.audit` |
| `apiEndpoint` | method + URL (no secrets) |
| `requestPayload`, `responsePayload` | masked, truncated to 10,000 chars |
| `responseCode`, `executionTimeMs`, `apiInvokedAt` | from the call |
| `status` | `SUCCESS` / `BUSINESS_FAILURE` / `TECHNICAL_FAILURE` / `TIMEOUT` |
| `errorMessage` | masked |

lendercore reads `requestType` as an enum (`ApiRequestType`), so only its
existing values (`PRECHECK_DEDUPE`, `PRECHECK_TIMBLE`, `PRECHECK_STATUS`, …)
may be written until lendercore adds new ones.

### PII masking

Masks `piiPaths`, `pii: true` params, and global keys: `pan`, `aadhar`,
`aadhar_number`, `mobile_number`, `customerPhone`, `email`, `dob`,
`date_of_birth`, `address_value`, `id_value`. Keeps last 4 chars (`XXXXXX1234`).

### Metrics

| Metric | Tags |
|---|---|
| `lih_l1_task_seconds` | `recipe`, `version`, `lender`, `result` (outcome or error class) |
| `lih_l1_operation_seconds` (from `MetricsInterceptor`) | `recipe`, `step`, `op`, `kind`, `result` (`ok`, `transport`, or error class) |
| `lih_l1_http_client_seconds` | `resource`, `method`, `status` |
| `lih_l1_expr_errors_total` | `recipe`, `path` |
| `lih_l1_code_ops_total` | `recipe`, `op` |

Logs carry MDC: `caseId`, `runNo`, `recipe`, `recipeVersion`, `stepId`, `opRef`, `lender`, `loanRequestId`.
Traces: span per task, child span per step, client span per call; workflow id as attribute.

---

## 12. Registry, publish and versioning

Built in M4. Author guide: [`l1-config-publishing.md`](l1-config-publishing.md).

Database `lih_config` (`MongoConfigStore`). Specs are stored as JSON **strings**: config keys such as `$each`
or dotted names are not valid Mongo field names, and the stored text is exactly what was validated.

| Collection | Key | Holds | Mutable |
|---|---|---|---|
| `l1_recipe` | `{ref, version}` unique | `spec`, `specHash`, `publishedBy/At`, `reason`, `publishId` | No (duplicate key refused) |
| `l1_operation` | `{ref, version}` unique | same | No |
| `l1_pointer` | `_id = TYPE:ref` | `latest`, `history[]` of moves (who, when, why, publishId) | Yes (audited) |
| `l1_resource` | `_id = resource` | current `spec`, `specHash`, who/when/why | Yes |
| `l1_resource_history` | `{resource, changedAt}` | every resource write | Append-only |
| `l1_redirect` | `{ref, fromVersion, at}` | forced redirect + who/when/why; newest wins | Append-only |
| `l1_publish_log` | `_id = publishId` | every real publish attempt with its full report | Append-only |

**Versions** are declared in git (missing = 1). A published `ref@N` never changes. A new version must be higher
than `latest`. Gaps are allowed, because QA may see versions that prod skips. `specHash` (SHA-256 of the parsed
document, keys sorted, `version` removed) decides "same content", so reformatting is not a change.

### Publish pipeline (`Publisher`, run from CI through `L1ConfigCli`)

1. Load the bundle (`ConfigBundle`): strict JSON (duplicate keys rejected), then the JSON Schema
   (`l1-schemas/*.schema.json`), then strict parsing into the model. Every error is collected, not just the first.
2. Versions against the registry: an existing `ref@N` must have the same `specHash`; a new one must be higher
   than `latest`.
3. Every HTTP resource has a block for the target environment.
4. Compile with the worker's own `ConfigCompiler` and executors. This covers references (bundle first, then the
   registry), CEL in every scope, dataflow, declared outcomes and outputs, and kind checks.
5. Golden tests (`GoldenRunner`). Every recipe's highest version in the bundle needs at least one.
6. If everything passed and this is a real publish, write in this order:
   - insert new operation versions;
   - insert new recipe versions;
   - move pointers forward (this also repairs a pointer left behind by an interrupted publish);
   - write changed resources.

   Re-running a publish is safe.
7. Real publishes (passed or failed) are written to `l1_publish_log`. `validate` and `--dry-run` write nothing.

Offline `validate` (no registry) skips step 2, and then recipes may only pin operations that are in the bundle.

### Resolution and caching

- At case start, L2 calls `RegistryService.resolve(refs)` to get the `latest` version of each recipe. L2 passes
  the pinned `recipeVersion` in `TaskRequest`.
- `StoreRecipeSource`:
  - Compiled recipes and operations are cached by `ref@version` in Caffeine, with no expiry (max 10,000 each), because versions are immutable.
  - A version published after start-up is loaded and compiled on first use.
  - `latest` and redirects are read on every call, so a publish or a redirect takes effect immediately, including for open cases on their next step.
- `StoreResourceProvider`: resources are cached for `l1.registry.resource-ttl` (60 s).
- Start-up (`warmUp`) compiles every stored recipe version and its operations, and refuses to start if one no
  longer compiles (for example, a removed `rk.*` function or kind).
- Redirects apply to recipes only; operations are pinned inside immutable recipes.

`l1.registry.mode=classpath` (the default) keeps the M1–M3 behaviour: it loads `l1.config-location` through the
same compiler. Use it for local development and tests.

**Not built yet:**
- retiring old versions: needs a Temporal visibility query to confirm that no open case pins them;
- storing the `rk.*` library version with each version: start-up compilation covers the main risk.

---

## 13. Security

| Risk | Control |
|---|---|
| Expression runs arbitrary code | CEL only; plain data in; native types off; limits |
| Request to an unexpected host | Host only from resource; path validation; URL-encoding |
| Mongo injection | Fixed shapes; bound values; forbidden operators; collection allowlist |
| Secret leak | `secretRef` only; resolved at call time; never logged; never in CEL |
| PII in Temporal history | Customer data in memory only; `OutputGuard` |
| PII in audit/logs | `PiiMasker` everywhere |
| Unreviewed config in prod | Git + PR + CI-only publish + publish log |
| Bad version breaks running cases | Immutable, pinned versions; logged redirects only |
| Removed function/kind breaks old versions | Checked at publish and start-up |

---

## 14. Worked example: Axis Dedupe

> Written for M1–M3. The current recipe adds a new `client_loan_id` per attempt and lendercore's skip and re-run rules, and the rest of the Axis precheck is now built: see [`l1-axis-precheck.md`](l1-axis-precheck.md).

### Steps

| # | Step | Operation (kind) | Does | Legacy reference (lendercore) |
|---|---|---|---|---|
| 1 | `core` | `core.fetchCustomerByLoanRequest@1` (HTTP) | Customer from Core; empty → `PERMANENT` | `Core.java:42-78` |
| 2 | `existing` | `precheck.findByLoanRequest@1` (MONGO) | Find `LenderCustomerPrecheck` for LR + `axis` | `AxisPrecheckProcessor.java:36` |
| 3 | `alreadyDone` | guard + return | Exists **and** has `applicantId` → `ALREADY_INITIATED` | `:38,66-68` |
| 4 | `ids` | `gen.stableId@1` (GEN) | Reuse stored `client_loan_id` or UUID v5(LR + runNo) | `:50` (random UUID today) |
| 5 | `intent` | `precheck.upsertInitiated@1` (MONGO) | Upsert `client_loan_id`, status `initiated` **before** the call | `:65` (saved after today) |
| 6 | `request` | `map.axis.precheckRequest@1` (MAP) | Axis body | `AxisApiService.java:1206-1244` |
| 7 | `call` | `axis.precheck.invoke@1` (HTTP) | POST `?type=dedupe` | `AxisClient.java:193-222` |
| 8 | `save` | `precheck.markDedupeDone@1` (MONGO) | `applicantId`, status `processed` | `AxisPrecheckProcessor.java:74-93` |
| – | return | – | `INITIATED` + `{applicantId, clientLoanId}` | – |

Behaviour change vs legacy (to confirm): a failed dedupe returns
`REJECTED` (lender said no) or raises, instead of being logged and ignored.

### Recipe

```json
{
  "recipe": "axis.precheck.dedupe",
  "lender": "axis",
  "inputs":   { "loanRequestId": "string", "runNo": "int" },
  "outputs":  { "applicantId": "string", "clientLoanId": "string" },
  "outcomes": ["INITIATED", "ALREADY_INITIATED", "REJECTED"],
  "auditKeys": { "loanRequestId": "input.loanRequestId", "customerId": "steps.?core.?customer.?id.orValue(null)" },
  "steps": [
    { "id": "core", "op": "core.fetchCustomerByLoanRequest@1",
      "in": { "loanRequestId": "input.loanRequestId" } },
    { "id": "existing", "op": "precheck.findByLoanRequest@1",
      "in": { "loanRequestId": "input.loanRequestId", "lender": "'axis'" } },
    { "id": "alreadyDone",
      "when": "steps.existing.found && steps.existing.doc.?applicantId.orValue(null) != null && steps.existing.doc.applicantId != ''",
      "return": { "outcome": "ALREADY_INITIATED",
                  "out": { "applicantId": "steps.existing.doc.applicantId",
                           "clientLoanId": "steps.existing.doc.client_loan_id" } } },
    { "id": "ids", "op": "gen.stableId@1",
      "in": { "reuse": "steps.existing.found ? steps.existing.doc.?client_loan_id.orValue('') : ''",
              "seed":  "input.loanRequestId + ':r' + string(input.runNo) + ':axis-dedupe'" } },
    { "id": "intent", "op": "precheck.upsertInitiated@1",
      "in": { "loanRequestId": "input.loanRequestId", "lender": "'axis'",
              "customerId": "steps.core.customer.id", "clientLoanId": "steps.ids.value" } },
    { "id": "request", "op": "map.axis.precheckRequest@1",
      "in": { "customer": "steps.core.customer", "clientLoanId": "steps.ids.value" } },
    { "id": "call", "op": "axis.precheck.invoke@1",
      "in": { "type": "'dedupe'", "body": "steps.request.value" },
      "onError": { "BUSINESS": "return:REJECTED" } },
    { "id": "save", "op": "precheck.markDedupeDone@1",
      "in": { "loanRequestId": "input.loanRequestId", "lender": "'axis'",
              "applicantId": "steps.call.applicantId" } }
  ],
  "return": { "outcome": "INITIATED",
              "out": { "applicantId": "steps.call.applicantId", "clientLoanId": "steps.ids.value" } }
}
```

### Operations

```json
{
  "op": "core.fetchCustomerByLoanRequest", "kind": "HTTP", "resource": "core",
  "params": { "loanRequestId": { "type": "string" } },
  "request": {
    "method": "GET",
    "path": "=env.customerDataPath",
    "query": { "loanRequestIds": "=params.loanRequestId", "loanIds": "", "lpLoanIds": "",
               "uLoanIds": "", "uLpLoanIds": "" }
  },
  "success":  "resp.status == 200 && resp.body.?loandata.orValue([]).size() > 0",
  "classify": "resp.status == 200 ? 'PERMANENT' : resp.status >= 500 ? 'TECHNICAL' : 'BUSINESS'",
  "out": { "customer": "resp.body.loandata[0].customer" },
  "required": ["customer"]
}
```

```json
{
  "op": "axis.precheck.invoke", "kind": "HTTP", "resource": "axis-precheck",
  "params": { "type": { "type": "string" }, "body": { "type": "map", "pii": true } },
  "request": {
    "method": "POST",
    "path": "/investor_integration/actions/invoke_api",
    "query": { "type": "=params.type" },
    "body": "=params.body"
  },
  "success":  "resp.status == 200 && resp.body.?errors.orValue([]).size() == 0 && has(resp.body.data)",
  "classify": "resp.status == 500 ? 'UNKNOWN' : resp.status >= 500 ? 'TECHNICAL' : 'BUSINESS'",
  "out": { "applicantId": "resp.body.?data.?applicantId.orValue(null)" },
  "audit": "={'dedupe': 'PRECHECK_DEDUPE', 'dedupe_timble': 'PRECHECK_TIMBLE', 'dedupe_status': 'PRECHECK_STATUS'}[params.type]",
  "piiPaths": ["$.aadhar_number", "$.dedupe_ids[*].id_value", "$.mobile_number", "$.email",
               "$.date_of_birth", "$.date_of_birth_as_per_pan", "$.address[*].address_value"]
}
```

```json
{
  "op": "map.axis.precheckRequest", "kind": "MAP", "resource": "axis-precheck",
  "params": { "customer": { "type": "map", "pii": true }, "clientLoanId": { "type": "string" } },
  "template": {
    "agreement_id":   "=env.preCheckAgreementId",
    "customer_id":    "=params.customer.id",
    "client_loan_id": "=params.clientLoanId",
    "model_name": "CLM1",
    "customer_type": "individual",
    "first_name":  "=rk.nameSplit(params.customer, 'first')",
    "middle_name": "=rk.nameSplit(params.customer, 'middle')",
    "last_name":   "=rk.nameSplit(params.customer, 'last')",
    "name_as_per_pan": "=params.customer.?customerPanName.orValue(null)",
    "date_of_birth":            "=params.customer.?dob.orValue(null)",
    "date_of_birth_as_per_pan": "=params.customer.?dob.orValue(null)",
    "mobile_number": "=params.customer.?customerPhone.orValue(null)",
    "email":  "=params.customer.?email.orValue(null)",
    "gender": "=params.customer.?gender.orValue(null)",
    "dedupe_type": "single",
    "aadhar_number": "=params.customer.?aadhar.orValue(null)",
    "dedupe_ids": [
      { "id_type": "PAN",    "id_value": "=params.customer.?pan.orValue(null)" },
      { "id_type": "AADHAR", "id_value": "=params.customer.?aadhar.orValue(null)" }
    ],
    "address": [
      { "address_type": "PERMANENT",
        "address_value":   "=regex.replace(params.customer.customerAddress, r'\\s+', ' ').trim()",
        "address_city":    "=params.customer.permanentAddress.city",
        "address_state":   "=params.customer.permanentAddress.state",
        "address_pincode": "=params.customer.permanentAddress.pin" },
      { "address_type": "COMMUNICATION",
        "address_value":   "=regex.replace(params.customer.customerAddress, r'\\s+', ' ').trim()",
        "address_city":    "=params.customer.?presentAddress.?city.orValue(null)",
        "address_state":   "=params.customer.?presentAddress.?state.orValue(null)",
        "address_pincode": "=params.customer.?presentAddress.?pin.orValue(null)" }
    ]
  }
}
```

```json
{ "op": "precheck.findByLoanRequest", "kind": "MONGO", "resource": "lendercore-mongo",
  "params": { "loanRequestId": { "type": "string" }, "lender": { "type": "string" } },
  "collection": "LenderCustomerPrecheck", "action": "FIND_ONE",
  "filter": { "loanRequestId": ":loanRequestId", "lenderSlug": ":lender" },
  "out": { "found": "result != null", "doc": "result" } }

{ "op": "precheck.upsertInitiated", "kind": "MONGO", "resource": "lendercore-mongo",
  "params": { "loanRequestId": { "type": "string" }, "lender": { "type": "string" },
              "customerId": { "type": "string" }, "clientLoanId": { "type": "string" } },
  "collection": "LenderCustomerPrecheck", "action": "UPSERT",
  "filter": { "loanRequestId": ":loanRequestId", "lenderSlug": ":lender" },
  "setOnInsert": { "customerId": ":customerId", "precheckStatus": "initiated", "approved": false,
                   "responseMap": {}, "creation_date": ":sys.now", "createdAt": ":sys.now",
                   "_class": "com.rupeek.lender.entity.PrecheckEntity" },
  "set": { "client_loan_id": ":clientLoanId", "last_updated": ":sys.now" },
  "inc": { "version": 1 } }

{ "op": "precheck.markDedupeDone", "kind": "MONGO", "resource": "lendercore-mongo",
  "params": { "loanRequestId": { "type": "string" }, "lender": { "type": "string" },
              "applicantId": { "type": "string" } },
  "collection": "LenderCustomerPrecheck", "action": "UPDATE_ONE",
  "filter": { "loanRequestId": ":loanRequestId", "lenderSlug": ":lender" },
  "set": { "applicantId": ":applicantId", "precheckStatus": "processed", "last_updated": ":sys.now" },
  "inc": { "version": 1 },
  "expect": { "matched": 1 } }
```

`gen.stableId@1` is a built-in platform operation (`kind: GEN`, `action: STABLE_ID`).

### Rerun behaviour

Worker dies after step 7, before step 8 → Temporal retries → step 3 finds no
`applicantId` → step 4 reuses the stored `client_loan_id` → Axis receives
the same id. Whether Axis treats that as the same application is an open
question (see decision log, Q5). Until confirmed, `axis.precheck.invoke` is
**not** `idempotent`, so a timeout after sending is `UNKNOWN`, not retried.

---

## 15. Testing

| Level | Tooling | Covers |
|---|---|---|
| Unit | JUnit 5 | `rk.*`, `TemplateEvaluator`, `ErrorClassifier`, `PiiMasker`, `OutputGuard` |
| Executor | WireMock, Testcontainers Mongo | HTTP timeouts/status/auth; Mongo actions, `expect`, forbidden operators |
| Engine | In-memory registry, `OperationMocks` | Guards, early return, `onError`, failure types |
| Activity | Temporal test environment | `RunTask` end to end; `ApplicationFailure` types; heartbeats |
| Golden | `GoldenRunner` (publish step; files under `l1-config/tests/`) | Inputs + mocked ops → outcome, outputs, calls; required for every recipe |
| Rerun | `AxisDedupeRerunTest`: real Mongo + WireMock, worker "dies" right after each operation's side effect, then the same `RunTask` reruns | One document, one `client_loan_id` in every lender call, right outcome, second lender call only after a crash between call and save |
| Parity | (1) `AxisDedupeParityTest`: test-only copy of the legacy mapping as oracle, 18+ customer shapes; (2) `L1ConfigCli parity`: legacy bodies from `LenderApiAuditLog`, customers re-read from Core, lender mocked | Request bodies match legacy field by field (`JsonDiff`: key order ignored, missing ≠ null) |
| Contract | Lender UAT | Real call before go-live / after lender API change |

Golden file:

```json
{
  "name": "fresh customer, dedupe accepted",
  "recipe": "axis.precheck.dedupe",
  "input": { "loanRequestId": "LR-TEST-1", "runNo": 1 },
  "mocks": {
    "core.fetchCustomerByLoanRequest@1": { "resp": { "status": 200, "body": { "loandata": [ { "customer": { "id": "C1", "customerProofName": "RAVI KUMAR" } } ] } } },
    "precheck.findByLoanRequest@1":      { "result": null },
    "axis.precheck.invoke@1":            { "resp": { "status": 200, "body": { "data": { "applicantId": "A9" } } } }
  },
  "expect": {
    "outcome": "INITIATED",
    "out": { "applicantId": "A9" },
    "calls": { "axis.precheck.invoke@1": { "params.body.first_name": "RAVI", "params.body.last_name": "KUMAR" } }
  }
}
```

Operations without a mock run against `WriteRecorder` (Mongo writes) so tests
can assert writes. Fixtures from real traffic are masked before commit.

CI for config: `validate` (schema, refs, CEL, dataflow) → `test` (golden,
rerun) → on merge `publish --env qa` → manual approval → `publish --env prod`.

---

## 16. Deployment and capacity

Expected load is small (≈ 500 cases/day × 10–12 steps).

| Item | POC setting |
|---|---|
| Replicas | 2 |
| Task queue | `lih-l1`; `maxConcurrentActivityExecutionSize` 50 per replica |
| HTTP | One `HttpClient` per resource; pool size from resource |
| Mongo | Pool 20 to the lendercore cluster; separate client for `lih_config` |
| Start-up | Compile all config → refuse to start on errors → start polling |
| Shutdown | Stop polling; finish running activities up to 60 s |
| Health | Liveness: process; readiness: config compiled, Mongo + Temporal reachable |
| Config | `l1.enabled` (default `false`), `l1.env` (`local`/`qa`/`uat`/`prod`), `l1.config-location` (`classpath:l1-config`), `l1.mongo-datasource`, `l1.registry.{mode,uri,database,resource-ttl}`, `l1.temporal.{enabled,target,namespace,task-queue,max-concurrent-activities}`, `l1.audit.{enabled,database,queue-capacity}`, `l1.dev.probe-workflow` |
| Wiring | `L1AutoConfiguration` is a Spring Boot auto-configuration (no component scan needed) and is off unless `l1.enabled=true`; start-up fails fast if config does not compile or Temporal is unreachable |

---

## 17. Reconciliation with L2 (later)

Not done now; listed so nothing is lost.

| Touchpoint | L1 position |
|---|---|
| Activity contract | L2 calls `RunTask(TaskRequest)` and routes on `TaskResult.outcome`; `ResolveRecipes` pins versions at case start (built in M6; see [`l1-for-l2.md`](l1-for-l2.md)) |
| Task queue | L1 proposes `lih-l1`; align with L2's queue strategy |
| Decision logic in L2 (`DECIDE` over raw lender fields) | Moved into L1 recipes as outcomes (M6: `axis.precheck.status`, `axis.precheck.timbleCallback`) |
| Callbacks | L2 receives the event, calls `axis.precheck.timbleCallback` with `inputs.event`; its output `loanRequestId` identifies the case |
| Persist / notify sinks | Built as L1 recipes (M6): saves inside the Axis recipes, `precheck.notify.verified` / `precheck.notify.failed` |
| Expression language | L1 is CEL; L2 converging on CEL avoids two evaluators |
| Version pinning | L2 manifest calls `RegistryService.resolve` at case start |
| Error handling | L2 maps `L1_*` failure types to retry / park / fail |

---

## 18. POC milestones

1. **Foundations** — `api`, `model`, JSON Schemas, CEL spike, first `rk.*`. *Exit:* every Axis Dedupe expression compiles and evaluates against a recorded response.
2. **Operation kinds + interceptors** — `HTTP`, `MONGO`, `MAP`, `GEN`; audit, masking, metrics, tracing. *Exit:* executor tests pass (WireMock, Testcontainers).
3. **Engine + activity** — `RecipeRunner`, classification, `OutputGuard`, `TaskActivityImpl`, worker start-up (classpath registry). *Exit:* `RunTask` runs Axis Dedupe in the Temporal test environment, all failure types covered.
4. **Registry + tooling** — Mongo registry, caches, publish pipeline, CLI, CI. *Exit:* a merged config change is published to QA by CI. *Status:* built. Locally verified: publish to `lih_config`, the worker runs a newly published version and a redirect without a restart. The CI workflow is written and needs the QA/prod registry secrets.
5. **Axis Dedupe** — golden, rerun and parity tests. *Exit:* request bodies match legacy for a sample of past loan requests. *Status:* built. Parity found and fixed three differences (`application_id: null` missing; numeric phone/pincode sent as numbers instead of strings). It matches the legacy oracle on every customer shape, and matches one real QA payload apart from `client_loan_id` (different by design). Still to do: run `parity` on a QA sample (needs Core QA token + read access to QA `LenderApiAuditLog`).
6. **Rest of Axis precheck** — Timble, callback mapping, status, record result, notify recipes; wire to L2. *Exit:* one Axis precheck end to end in QA against Axis UAT. *Status:* recipes built (six, see [`l1-axis-precheck.md`](l1-axis-precheck.md)). Parity with the legacy logic is tested, and the full flow runs end to end through a reference L2 workflow (`ReferenceAxisPrecheckFlowTest`) and on local Temporal. L2 wiring is documented in [`l1-for-l2.md`](l1-for-l2.md). Still to do: L2 adopts it, then a QA run against Axis UAT.
