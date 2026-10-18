# Running L1 on a local Temporal

Runs `RunTask` on a real Temporal server without L2. A dev-only `L1Probe` workflow calls `RunTask` once,
with the activity options L2 is expected to use: only `L1_TECHNICAL` is retried (4 attempts), and
`L1_BUSINESS`, `L1_UNKNOWN` and `L1_PERMANENT` end the workflow at once. It is registered only when
`l1.dev.probe-workflow=true`.

## Start

Use one terminal for each command:

```bash
dev/l1-local/l1-local.sh infra   # Temporal dev server (gRPC :7233, UI http://localhost:8233) + Mongo :27018
dev/l1-local/l1-local.sh stubs   # WireMock on :8089 for Core and Axis
dev/l1-local/l1-local.sh app     # LIH app with l1.enabled=true, l1.env=local, probe workflow on
```

When the app is up, it logs `L1 worker polling task queue 'lih-l1'`.

### Running published versions (registry mode)

By default the app loads `src/main/resources/l1-config` from the classpath. To run what is published in the
local `lih_config` registry instead:

```bash
dev/l1-local/l1-local.sh publish                     # validate + golden + publish to mongodb://localhost:27018/lih_config
L1_REGISTRY=mongo dev/l1-local/l1-local.sh app       # logs "L1 registry: compiled N recipe version(s)"
dev/l1-local/l1-local.sh config status               # what is live
dev/l1-local/l1-local.sh probe LR-OK-5 1 2           # run recipe version 2 (3rd argument)
```

Publishing a new version while the app is running takes effect without a restart. See
[`l1-config-publishing.md`](l1-config-publishing.md).

## The whole Axis precheck in one workflow (demo)

`app` also starts a dev-only reference L2 workflow on queue `l2-reference`. It does what L2 will do: one workflow
per case, calling the L1 activities on `lih-l1`.

Use three terminals: `infra`, `stubs` and `app` (as above). Keep the **app** terminal visible: every case logs
`[L2 <case>]` lines (the workflow's decisions) and `[L1 <case>]` lines (each task, step and call). In a fourth:

```bash
dev/l1-local/l1-local.sh precheck LR-OK-1          # approved: dedupe → timble → callback → status (WIP, then Y) → notify
dev/l1-local/l1-local.sh precheck LR-OK-2 N        # Timble name mismatch → status FAILURE → notify failed → REJECTED
dev/l1-local/l1-local.sh precheck LR-OK-3 Y 30     # poll status every 30 s instead of 10 s
dev/l1-local/l1-local.sh timeline axis-precheck-LR-OK-1-r1     # print the timeline again
```

`precheck`:
1. starts the workflow (`axis-precheck-<loanRequestId>-r1`);
2. sends the Timble callback as a signal, like L2's callback endpoint would;
3. waits for the result;
4. prints a timeline of every activity with its outcome, plus the signal and the timers.

The first status poll answers `WIP` on purpose, so you see a PENDING → wait → poll again. Use a new loanRequestId
for each run, or the dedupe step finds the earlier record. The same run is in the Temporal UI at
http://localhost:8233.

To follow only one case in the app log, filter it, for example: `grep "LR-OK-1-r1"`.
Logs never contain request or response bodies: only ids, outcomes, URLs without query, Mongo actions and timings.

## Run a scenario

```bash
dev/l1-local/l1-local.sh probe LR-OK-1        # optional: runNo (default 1), recipeVersion (default 1)
dev/l1-local/l1-local.sh run-recipe axis.precheck.status '{"loanRequestId":"LR-OK-1"}'   # any recipe
```

The stubs also answer Timble (`reference_id` `TIMBLE-LOCAL-1`), status (all `Y`) and both rupeekweb
notifications, so the whole Axis precheck can be run one recipe after another (see
[`l1-axis-precheck.md`](l1-axis-precheck.md)).

The stubs choose the scenario from the `loanRequestId` prefix:

| loanRequestId | Core | Axis dedupe | Expected |
|---|---|---|---|
| `LR-OK-*` | customer `CUST-OK` | 200 with `applicantId` | `INITIATED`; run it again for `ALREADY_INITIATED` |
| `LR-REJECT-*` | `CUST-REJECT` | 200 with `errors[]` | `REJECTED` (BUSINESS, handled by `onError`) |
| `LR-503-*` | `CUST-503` | 503 | `L1_TECHNICAL`, retried: 4 Axis calls, 4 audit rows |
| `LR-500-*` | `CUST-500` | 500 | `L1_UNKNOWN`, not retried: 1 Axis call |
| `LR-NOCORE-*` | empty `loandata` | not called | `L1_PERMANENT` at step `core` |

## Check

- **Temporal UI** (http://localhost:8233): workflow input and result, the step trace, the activity attempts
  and the failure type.
- **Mongo** (`mongodb://localhost:27018/lendercore`):
  - `LenderCustomerPrecheck` has one row per loanRequestId, with `client_loan_id` and, after success,
    `applicantId`;
  - `LenderApiAuditLog` has one row per Axis call, with PII masked.
- **WireMock journal**: `curl localhost:8089/__admin/requests`.

## Real endpoints instead of stubs

Export these before `app` (the script only sets stub defaults when they are unset):
`CORE_BASEURL`, `CORE_TOKEN`, `AXIS_PRECHECK_BASEURL`, `AXIS_PRECHECK_API_KEY`. This calls Axis for real.
Only use UAT credentials, and test loan requests.

## Reset

```bash
docker exec l1-mongo mongosh -quiet lendercore --eval 'db.LenderCustomerPrecheck.drop(); db.LenderApiAuditLog.drop()'
docker exec l1-mongo mongosh -quiet lih_config --eval 'db.dropDatabase()'   # the local registry
rm -f target/l1-temporal.db   # Temporal history (with infra stopped)
```
