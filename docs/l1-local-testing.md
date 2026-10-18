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

## Run a scenario

```bash
dev/l1-local/l1-local.sh probe LR-OK-1        # optional 2nd arg: runNo (default 1)
```

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
rm -f target/l1-temporal.db   # Temporal history (with infra stopped)
```
