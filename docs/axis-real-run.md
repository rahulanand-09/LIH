# Running the Axis precheck against real QA endpoints

What to export, what it writes, how to start a case, and what will not match LenderCore yet. The step-by-step
comparison with LenderCore is in [`axis-parity.md`](axis-parity.md).

## 1. Environment variables

All endpoints and credentials come from environment variables read by the resource files in
`src/main/resources/flow-config/resources/*.json`. With `flow.env=local` (the default) every base URL is an
env var; the `qa`/`prod` blocks keep literal URLs for core and rupeekweb and are only used if `flow.env` is set
to them. Nothing secret is stored in the repo.

### Needed for a real run

| Variable | Purpose | LenderCore property it maps to | Example (non-secret) |
|---|---|---|---|
| `AXIS_PRECHECK_BASEURL` | Base URL of the Axis precheck host; calls go to `<base>/investor_integration/actions/invoke_api?type=dedupe\|dedupe_timble\|dedupe_status` | `axis.preCheckBaseurl` (`${profile}.axis.preCheckBaseurl`; the path is `axis.initiatePreCheck`, fixed in the task JSON) | `https://<axis-qa-host>` (take the value from LenderCore's `application-qa.properties`) |
| `AXIS_PRECHECK_API_KEY` | Secret sent as the `API-key` header on all three Axis calls | `axis.preCheckApiKey` | secret, not shown |
| `AXIS_PRECHECK_AGREEMENT_ID` | `agreement_id` in the dedupe, timble and status bodies. **Required** with `flow.env=local` (a missing value fails the task with a clear message instead of sending a blank). With `flow.env=qa`/`prod` it defaults to `2181`/`323` | `axis.preCheckAgreementId` | `2181` (LenderCore's qa value) |
| `CORE_BASEURL` | Core service base URL; the call is `GET <base>/api/support/fetchloandetailsv2?loanRequestIds=<id>&loanIds=&lpLoanIds=&uLoanIds=&uLpLoanIds=` | `core.coreUrl` | `https://coresvc-red-qa.rupeek.co` (LenderCore's qa value) |
| `CORE_TOKEN` | Secret sent as the `Authorization` header to core (raw value, no `Bearer`) | `core.coreToken` | secret, not shown |
| `RUPEEKWEB_BASEURL` | rupeekweb base URL for the two notifications (`/api/loanrequest/precheck-verification`, `/api/lender/updateprecheckfailure`) | `rupeekweb.endpoint` (`RUPEEKWEB_ENDPOINT`) | `https://<rupeekweb-qa-host>` |
| `RUPEEKWEB_ADMIN_TOKEN` | Secret sent as `Authorization` to rupeekweb | `rupeekweb.adminToken` | secret, not shown |

### Spring / flow settings (defaults already match the local setup)

| Variable (relaxed binding) | Purpose | Default |
|---|---|---|
| `SPRING_DATA_MONGODB_URI` | The app's Mongo: business collections (`lendercore` DB) | `mongodb://localhost:27018/lendercore` (`application.yml`) |
| `FLOW_ENV` | Which resource block to use (`local`, `qa`, `uat`, `prod`) | `local` |
| `FLOW_TEMPORAL_TARGET` / `FLOW_TEMPORAL_NAMESPACE` | Temporal frontend | `localhost:7233` / `default` |
| `FLOW_TEMPORAL_TASKQUEUE` | Task queue the worker polls | `lih-l2` |
| `FLOW_REGISTRY_MODE`, `SPRING_DATA_REDIS_HOST` | Registry mode (`mongo`) and its Redis read cache | `mongo`, `localhost:6379` |

### LenderCore properties the Axis precheck does NOT use

`axis.productId`, `axis.productIdCLA1/CLA2`, `axis.agreementId`, `axis.agreementIdCLA1/CLA2`, `axis.apiKey`,
`axis.lenderId`, `axis.partnerSchemeCode`, `axis.baseurl` and the loan endpoints belong to loan creation, not
precheck: `AxisClient.initiatePreCheck/initiatePreCheckTimble/checkPreCheckStatus` send only `API-key` (no
`Product-Id`) and `agreement_id = axis.preCheckAgreementId` (`AxisClient.java:193-281`). No LIH variable exists
for them, deliberately.

### What gets written where

| Store | What | Notes |
|---|---|---|
| `lih-mongo` (`localhost:27018`), DB `lendercore` | `LenderCustomerPrecheck`, `LenderCustomerPrecheckAudit`, `LenderApiAuditLog` | same collection and field names as LenderCore's entities; use these for a later diff |
| `lih-mongo`, DB `lih_flow_config` | the flow/task registry | must already contain the config versions of this branch (see section 3) |
| local Temporal dev server | workflows on task queue `lih-l2` | the app does not start Temporal |
| Redis `localhost:6379` | registry read cache | `application.yml` enables it |

## 2. Runbook

1. JDK 21: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"`.
2. Check the dependencies are up (do not restart them): the Temporal dev server on `7233`, `lih-mongo` on `27018`, Redis on `6379`.
3. Export the seven variables from section 1 (secrets from your own store; do not commit them):
   ```bash
   export AXIS_PRECHECK_BASEURL=...   AXIS_PRECHECK_API_KEY=...   AXIS_PRECHECK_AGREEMENT_ID=2181
   export CORE_BASEURL=...            CORE_TOKEN=...
   export RUPEEKWEB_BASEURL=...       RUPEEKWEB_ADMIN_TOKEN=...
   ```
4. Seed the registry (section 3), then start the worker: `mvn spring-boot:run` (`com.rupeek.LihWorkerApplication`, port 8091). It polls the Temporal task queue `lih-l2`.
5. Start a case once the controller track has landed:
   ```
   POST http://localhost:8091/flows/axis-precheck-decomposed/start
   {"inputs": {"loanRequestId": "<id>", "runNo": 1, "loanProcess": "<fresh|renewal|...>"}}
   ```
   The flow fetches the customer from core, calls Axis dedupe and timble, writes the precheck record and audit row, then **waits** for the Timble callback.
6. Deliver the Timble callback (the real Axis webhook, or by hand). Payload as LenderCore receives it on `POST /v1/callback?source=YUBI&purpose=TIMBLE_STATUS`:
   `{"product_id": "...", "customer_id": "<core customer id>", "timble_status": "...", "name_match": "Y|N", "aadhar_match": "Y|N", "timble_error_message": "..."}`.
   It is correlated by `customer_id` (latest precheck for that customer) and delivered to the workflow as the `timbleCallback` event (controller track).
7. The flow then polls Axis status every 60 s until no flag is `WIP`, writes the result, notifies rupeekweb (verification on success, failure profile on failure) and ends `APPROVED` / `REJECTED`.
8. Inspect: Temporal UI `http://localhost:8233`, and the three `lendercore` collections.

To re-run the same loan request after it completed, start it with the next `runNo`: the same `(loanRequestId, runNo)` is treated as a replay and does not call Axis again.

`FlowTriggerClient` cannot be used for a real case as it stands: it fires a canned callback for customer `CUST-TEST-1` right after starting, and defaults to queue `lih-l1`.

## 3. Registry versions

Registry versions are immutable and the app publishes nothing itself. Every task/flow file changed by this work
carries `"version": 2` (new task `axis.decomposed.dedupe.abortRemarks` is `1`). Until the controller track's seeding
lands, the registry in `lih-mongo` (`lih_flow_config`) can be brought up to date by starting `FlowWorkerDaemon` once:
its bootstrap now publishes any file whose `version` is higher than the registry's latest, then stop it (it also
starts a WireMock and polls queue `lih-l1`, so it is not the real-run worker). Nothing is overwritten: v1 stays
where it is, and cases started earlier keep their pinned versions.

## 4. What will NOT match LenderCore yet

From [`axis-parity.md`](axis-parity.md); open items first.

* **No concurrency lock** (LenderCore: Redis lock per customer+loan request, 5 min). LIH relies on one workflow per `loanRequestId` and the in-progress guard. The controller track must reject a duplicate workflow id.
* **Callback HTTP answer**: LenderCore answers Axis `200 {"status":"SUCCESS"}` or `400 {"status":"FAILURE"}` (when `name_match`/`aadhar_match` is `N`). The flow only exposes the decision; the controller track must produce the HTTP answer.
* **`LenderApiAuditLog`**: `apiEndpoint` carries the full URL with `?type=...` (LenderCore: base+path, query only for timble/status); no `changeHash`, no suppression of identical consecutive responses (so each status poll writes a row), no `_class`; payload PII is masked (LenderCore stores it raw).
* **Audit-outcome row choice**: LIH updates the first `processed` audit row, LenderCore the newest. Only differs if an older run left a row at `processed`.
* **`version` counters** on the Mongo documents will not equal LenderCore's.
* **Timble failure response**: a rejected/invalid Timble call ends the case `REJECTED` without writing anything; LenderCore stores a null `reference_id` and carries on (and then stays `PENDING` forever).
* **HTTP 5xx from Axis**: LenderCore treats it as an invalid response and persists a bare record; LIH retries (5 attempts) and then parks the node for an operator.
* **Failures in general**: LenderCore writes a `failure` audit row and returns 500; LIH parks the node (resume via the operator signal) and writes no failure row, except the re-run abort, which does write one.
* **rupeekweb notify errors**: LenderCore swallows them; LIH continues on 4xx and retries then parks on 429/5xx.
* **`client_loan_id`** is a stable UUIDv5 per `(loanRequestId, runNo)`, not a random UUIDv4.
* **Poll cadence** is 60 s inside the workflow; LenderCore polls only when its caller asks. The real caller's cadence is unknown.
* **Core path**: `customerDataPath` is `/api/support/fetchloandetailsv2` in every env block (LenderCore's uat uses `v4`).
* **Unverified**: the qa/prod literal URLs in `core.json` and `rupeekweb.json` were copied from earlier config, not re-derived; use the `local` block with env vars.
