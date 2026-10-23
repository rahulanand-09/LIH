# Logging, metrics and debugging

How to see what LIH is doing once it is deployed. Code lives in `com.rupeek.flow.observability` plus small
log/metric calls at the sites listed below. Nothing here changes engine behaviour.

## 1. Format and MDC

`src/main/resources/logback-spring.xml`:

- Profile `local` or `dev`: human-readable console line, MDC appended as `{k=v, ...}`.
- Any other profile (qa, uat, prod, none): **one JSON object per line** using Spring Boot's built-in structured
  logging (`logstash` format, no extra dependency). MDC keys are top-level JSON fields next to `message`,
  `level`, `logger_name`, `@timestamp`.
- Levels are the normal Spring ones (`logging.level.com.rupeek.flow=DEBUG`, or per logger, e.g.
  `logging.level.com.rupeek.flow.task.http=DEBUG`).

MDC keys (constants in `LogContext`):

| Key | Meaning | Set by |
|---|---|---|
| `loanRequestId` | the case's loan request, when the flow/task inputs carry one named `loanRequestId` | engine, activity interceptor |
| `workflowId`, `runId` | Temporal ids (child workflow ids are `<parent>:<nodeId>`) | engine, activity interceptor |
| `flowRef` | `flowRef@version` of the flow being walked (engine lines only: the activity does not know it) | engine |
| `nodeId` | graph node id (macro-prefixed, e.g. `call.inner`, for inline subflows) | engine, activity interceptor |
| `taskRef`, `kind`, `lender` | task being executed (`lender` = the task's `fields.lender`, if declared) | activity interceptor |
| `activityType`, `attempt` | Temporal activity type and attempt number (1 = first try) | activity interceptor |
| `requestId` | HTTP request id; `RequestIdFilter` accepts a sane `X-Request-Id` or generates a UUID and echoes it back | `RequestIdFilter` (the api package's own request logging uses the same key) |

Temporal's SDK also adds its own `WorkflowId`, `RunId`, `WorkflowType`, `TaskQueue`, `Namespace` MDC keys on
workflow-thread lines (capitalised, so they sit beside ours harmlessly).

`LogContext.with("k", "v", ...)` is the try-with-resources helper; it restores previous values on close.

### Replay safety (workflow code)

Verified against temporal-sdk 1.40.0: `Workflow.getLogger` returns `io.temporal.internal.logging.ReplayAwareLogger`,
which drops every line while the SDK replays history. `FlowWorkflowImpl` logs only through `WorkflowLog`, which uses
it. The SDK has no MDC support and workflow coroutines hop across pooled threads, so `WorkflowLog` never holds MDC
across workflow code: it sets the keys, logs on the same thread, and restores them in one call. Metrics from workflow
code are guarded with `Workflow.isReplaying()` for the same reason. `EngineObservabilityTest` replays a recorded
history and asserts zero workflow log lines and no extra metric increment.

Caveat: a worker that crashes mid-flow loses the lines it would have emitted for work whose history was already
written, and the metrics for that work (the replaying worker is silent by design). Treat counters as
"observed live", not an audit trail; Temporal history is the audit trail.

### PII and secrets

- Auth headers / API keys / tokens: never logged (request and response headers are never logged at all).
- URLs: user-info stripped; query values of `token|key|secret|password|signature|...` become `***`; values of
  params named like a PII key (`PiiMasker.GLOBAL_KEYS` or the last segment of the node's `piiPaths`) are masked
  (`XXXXXX1234`); any value found under a PII key in the call's inputs or a `piiPaths` key in the body is masked
  anywhere it appears in the URL (path included).
- Bodies: DEBUG only, through `PiiMasker.maskPayload(body, node piiPaths)` (same as `TaskAuditor`). A non-JSON body
  is logged as its length only. Responses not covered by a declared `piiPaths` are only as protected as
  `GLOBAL_KEYS` — declare `piiPaths` on any node whose response carries PII, as for audit.
- Failure text logged by the activity interceptor is run through `PiiMasker.maskText` with the call's PII-keyed
  input values, then truncated to 300 characters. The workflow-side PARK line logs only the failure *type*.
- Mongo lines log operation, db, collection and counts only: no filters, no documents.
- `LihWorkerInterceptor` never logs inputs. Proven by `HttpLogSafetyTest` (INFO: no key/PAN; DEBUG: masked only).

## 2. Log catalogue

Logger `workflow` = `com.rupeek.flow.engine.FlowWorkflowImpl` (all workflow lines). "Fields" = MDC keys present
besides the message's own key=value text. WF = `workflowId, runId, flowRef, nodeId, loanRequestId (if in inputs)`;
ACT = `workflowId, runId, activityType, attempt, taskRef, nodeId, kind, lender, loanRequestId`.

| Event | Level | Logger | Fields | Emitted at |
|---|---|---|---|---|
| flow start (resolved + pinned task/flow versions) | INFO | workflow | WF | `engine/FlowWorkflowImpl.java:128` |
| node enter | DEBUG | workflow | WF | `FlowWorkflowImpl.java:191` |
| node exit: outcome, **chosen edge** (`next=`), duration | INFO | workflow | WF | `FlowWorkflowImpl.java:105` (called from the sequential loop and each parallel branch) |
| subflow spawn, `mode=child` (child workflow) or `mode=inline` (spliced), child flowRef@version, depth | INFO | workflow | WF | `FlowWorkflowImpl.java:273` |
| WAIT entered / event received / timed out (WARN) | INFO / INFO / WARN | workflow | WF | `FlowWorkflowImpl.java:330`, `:339`, `:342` |
| PARK entered (reason = failure type) | WARN | workflow | WF | `FlowWorkflowImpl.java:361` |
| PARK resumed (action RETRY/SKIP) | INFO | workflow | WF | `FlowWorkflowImpl.java:366` |
| flow end with final status | INFO | workflow | WF | `FlowWorkflowImpl.java:138` |
| flow failed (non-PARK exception, e.g. ConfigError) | ERROR | workflow | WF | `FlowWorkflowImpl.java:72` |
| task start | DEBUG | `com.rupeek.flow.task` | ACT | `observability/LihWorkerInterceptor.java:82` |
| task finish: outcome, duration | INFO | `com.rupeek.flow.task` | ACT | `LihWorkerInterceptor.java:88` |
| task failed, **retry scheduled** (attempt, next attempt, delay from the activity's own RetryOptions, masked reason) | WARN | `com.rupeek.flow.task` | ACT | `LihWorkerInterceptor.java:110` |
| task failed, no retry (attempts exhausted / non-retryable type), "flow will PARK" | WARN | `com.rupeek.flow.task` | ACT | `LihWorkerInterceptor.java:115` |
| HTTP request (masked URL + masked body) | DEBUG | `com.rupeek.flow.task.http` | ACT | `observability/HttpCallLog.java:68` |
| HTTP call: resource, method, masked URL, status, latency | INFO | `com.rupeek.flow.task.http` | ACT | `HttpCallLog.java:74` |
| HTTP response masked body | DEBUG | `com.rupeek.flow.task.http` | ACT | `HttpCallLog.java:76` |
| HTTP verdict: `success=false`, CEL `classify`, retryable, mapped outcome | WARN | `com.rupeek.flow.task.http` | ACT | `HttpCallLog.java:87` |
| HTTP verdict success | DEBUG | `com.rupeek.flow.task.http` | ACT | `HttpCallLog.java:85` |
| HTTP transport failure (timeout / connect / I/O), classification, retryable | WARN | `com.rupeek.flow.task.http` | ACT | `HttpCallLog.java:94` |
| Mongo op, db, collection, matched/modified / inserted / returned / found counts | INFO | `com.rupeek.flow.task.mongo` | ACT | `observability/MongoCallLog.java:28` (called from `MongoTaskExecutor.execute`) |
| MAP / GEN | (outcome only, via task finish) | `com.rupeek.flow.task` | ACT | `LihWorkerInterceptor.java:88` |
| task publish ACCEPTED / REJECTED (+ CEL validation failure class and message) | INFO / WARN | `com.rupeek.flow.registry.TaskPublisher` | `requestId` if via API | `registry/TaskPublisher.java:75`, `:58`, `:69` |
| flow publish ACCEPTED / REJECTED (duplicate version) | INFO / WARN | `com.rupeek.flow.registry.FlowPublisher` | same | `registry/FlowPublisher.java:56`, `:50` |
| latest pointer moved | INFO | `...registry.store.SpringDataConfigStore` | same | `registry/store/SpringDataConfigStore.java:105` |
| cache hit / miss; cached pointer evicted | DEBUG | `...registry.store.RedisConfigStore` | — | `registry/store/RedisConfigStore.java:92`, `:78` |
| audit record dropped (queue full / not queued / write failed) | WARN | `...audit.MongoAuditWriter` | — | `audit/MongoAuditWriter.java:40`, `:52`, `:76` |
| worker started (queue, target, namespace) | INFO | `...worker.FlowTemporalWorker` | — | `worker/FlowTemporalWorker.java:75` |
| Temporal reachable / NOT reachable (ERROR; non-fatal, probe runs once at start) | INFO / ERROR | `...worker.FlowTemporalWorker` | — | `FlowTemporalWorker.java:86`, `:88` |
| startup banner: env, profiles, queue, namespace, registry/audit DB names, redis cache, seed flag (`flow.seed.enabled`, `n/a` if unset). No URIs, no secrets | INFO | `...observability.ObservabilityAutoConfiguration` | — | `observability/ObservabilityAutoConfiguration.java:52` |

A retry that Temporal schedules server-side is invisible to the workflow, which is why the retry decision is logged
from the activity side. The delay shown is Temporal's documented schedule (`initial * coefficient^(attempt-1)`,
capped at the max interval) computed from the activity's own `RetryOptions`; actual jitter may differ slightly.

## 3. Metrics

Micrometer, exposed at `/actuator/prometheus` (also `/actuator/health`, `/actuator/info`; list in
`management.endpoints.web.exposure.include`). All metrics carry a common tag `application=lih`. Dependencies:
`spring-boot-starter-actuator` (brings Micrometer and the health endpoint) and `micrometer-registry-prometheus`.
Tags are config-defined names or tiny enums: **never** `loanRequestId` or `workflowId`.

| Name | Type | Tags | Meaning |
|---|---|---|---|
| `lih.flow.node.outcomes` | counter | `flowRef` (no version), `node` (node id), `outcome` | a node finished with this outcome label (live executions only, not replay) |
| `lih.flow.park.events` | counter | `flowRef`, `node`, `event` = `entered`/`resumed` | PARK entered / operator resumed. Parked now is roughly `entered - resumed` |
| `lih.task.executions` | counter | `taskRef` (`inline` if none), `kind`, `outcome` (label, or `FAILED_<TYPE>`) | task activity attempts, one per attempt |
| `lih.task.duration` | timer (+histogram) | `taskRef`, `kind` | task activity attempt duration |
| `lih.task.retries` | counter | `taskRef`, `kind`, `reason` (failure type) | an attempt failed and Temporal will retry it |
| `lih.http.calls` | counter | `resource`, `status` = `2xx`/`4xx`/`5xx`/`error` | outbound HTTP calls (`error` = no response) |
| `lih.http.latency` | timer (+histogram) | `resource` | outbound HTTP latency |
| `lih.audit.dropped` | counter | `reason` = `queue_full`/`not_queued`/`write_failed` | audit rows lost |
| `lih.registry.publish` | counter | `type` = `FLOW`/`TASK`, `result` = `accepted`/`rejected` | publishes |
| `lih.registry.cache` | counter | `what` = `version`/`pointer`, `result` = `hit`/`miss` | Redis registry cache |

Plus the standard JVM, system and HTTP-server metrics that actuator registers.

**Not provided: a "currently parked cases" gauge.** Parked state lives only inside workflow instances; a per-worker
gauge would be wrong after a restart, a cache eviction or a replay, and a correct one needs a Temporal visibility
query on a search attribute (`ExecutionStatus=Running` plus a PARKED marker). Use `lih.flow.park.events` for the
rate and the Temporal UI/`FlowWorkflow.query()` for the exact list.

## 4. Debugging a stuck case

Start from the `loanRequestId`.

1. **Find the workflow.** Workflow ids for the Axis flows are `axis-precheck-<loanRequestId>`
   (child flows: `<parent id>:<nodeId>`). In the Temporal UI (local `localhost:8233`): Workflows ->
   filter `WorkflowId STARTS_WITH 'axis-precheck-<loanRequestId>'` to see the parent and every child flow, with status.
   In your log tool: `loanRequestId:"<id>"` (JSON field) — this returns workflow lines and task lines together.
   Not every task line has the id: it appears only when the task's `in` wires an input named `loanRequestId`; if a
   line is missing, filter by `workflowId` taken from a line that has both.
2. **Healthy case.** In time order you should see, per flow: `flow start flowRef=...@N depth=D pinnedVersions={...}`,
   then for each node `task finish ... outcome=X durationMs=..` (activity side, with `attempt=1`) followed by
   `node exit nodeId=.. outcome=X next=..` (workflow side), `subflow spawn mode=child|inline ...` where a flow is
   nested (each child then has its own `flow start`/`flow end`), and finally `flow end flowRef=.. status=..` for the
   root. HTTP nodes add one `http call ... status=200 latencyMs=..`, Mongo nodes one `mongo op=.. matched=..`.
   The last `node exit` tells you where the case is; its `next=` is the node that should be running now.
3. **Case is slow or silent.** The last `node exit` has a `next=Y` but no `task finish` for Y: the task is running,
   queued with no worker polling (check `LIH started`/`Flow worker polling` lines and `Temporal reachable`), or sitting in
   a retry backoff. Look for `task failed ... retryScheduled=true nextAttempt=N delayMs=..`: it is being retried;
   the `reason=` and, for HTTP, the preceding `http verdict ... classify=TECHNICAL` or `http call failed ... error=`
   line say why. If the node is a WAIT: `wait entered ... event=E` with no `wait event received` means the callback
   never arrived (check the api request logs by `requestId`, and `lih.http.calls` / callback endpoint metrics).
4. **PARKed case.** Signature:
   - `task failed ... retryScheduled=false (attempts exhausted | non-retryable failure type) -> flow will PARK`
   - `PARK entered nodeId=Y reason=<TECHNICAL|PERMANENT|BUSINESS|UNKNOWN|ChildWorkflow...>` at WARN, and the workflow
     stays Running in the Temporal UI with no further events (it is blocked in a Workflow.await, not failed).
   - `lih.flow.park.events{event="entered"}` increments; `FlowWorkflow.query()` returns `nodeStatus[Y]=PARKED`.
   Read the WARN above it for the cause (masked), then fix the root cause (endpoint down, bad config) and send the
   resume signal (RETRY re-attempts with a fresh retry budget; SKIP continues with a synthetic outcome). You will see
   `PARK resumed nodeId=Y action=RETRY|SKIP` and the flow continuing. For a PARK inside a child flow, the
   PARK lines carry the child's `workflowId` (`<parent>:<nodeId>`): signal that id, not the parent's.
5. **Failed (not parked) workflow.** `flow failed ... error=ConfigError: No edge from X on outcome Y` (ERROR) means a
   node returned an outcome the graph has no edge for; the preceding `node exit`/`task finish` shows the outcome.
6. **Need the bodies?** Raise just the HTTP logger at runtime: `logging.level.com.rupeek.flow.task.http=DEBUG`
   (needs a restart; the `loggers` actuator endpoint is not exposed). You get the masked request/response bodies; secrets are
   never present. Mongo filters/documents are never logged; use the `LenderApiAuditLog` rows for those.
7. **Version confusion** ("which config did this case run?"): the `flow start` line lists `pinnedVersions` for the
   flow's tasks/subflows, and every `flowRef=name@N` shows the version in effect.
