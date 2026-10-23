# Axis precheck in L1

The whole Axis precheck as L1 recipes. lendercore (`release-AWS-2.0`) is the reference. Parity is proven by tests
against test-only copies of the legacy code (`src/test/java/com/rupeek/l1/parity/`). The full flow runs end to
end in `ReferenceAxisPrecheckFlowTest`.

For how L2 drives these recipes, see [`l1-for-l2.md`](l1-for-l2.md).

## Recipes

| Recipe | Inputs | Outcomes | Outputs | Side effects |
|---|---|---|---|---|
| `axis.precheck.dedupe` | `loanRequestId`, `runNo`, `loanProcess` | `INITIATED`, `ALREADY_INITIATED`, `IN_PROGRESS`, `REJECTED` | `applicantId`, `clientLoanId` | Axis `dedupe`; upsert `LenderCustomerPrecheck`; skip row in `LenderCustomerPrecheckAudit` |
| `axis.precheck.timble` | `loanRequestId`, `runNo`, `loanProcess` | `INITIATED`, `ALREADY_INITIATED`, `REJECTED` | `timbleReferenceId` | Axis `dedupe_timble`; `reference_id` on the precheck; `processed` audit row |
| `axis.precheck.timbleCallback` | `event` (callback payload) | `TIMBLE_OK`, `TIMBLE_FAILED`, `NO_PRECHECK` | `customerId`, `loanRequestId`, `timbleOk` | `responseMap.timble` on the customer's latest precheck |
| `axis.precheck.status` | `loanRequestId` | `SUCCESS`, `FAILURE`, `PENDING`, `ALREADY_FINAL`, `NO_PRECHECK` | `dedupe`, `pan`, `ramp`, `hunter`, `timble` (Y/WIP/N), `finalStatus` | Axis `dedupe_status`; flags + overall status + `approved`; audit row outcome |
| `precheck.notify.verified` | `loanRequestId` | `NOTIFIED`, `NOT_ACCEPTED` | – | rupeekweb `precheck-verification` (form, `approved=true`) |
| `precheck.notify.failed` | `loanRequestId`, `lender` | `NOTIFIED`, `NOT_ACCEPTED` | – | rupeekweb `updateprecheckfailure` (JSON with the precheck profile) |

The two notify recipes are lender-agnostic and work for any lender's precheck. Every Axis call is written to
`LenderApiAuditLog` as `PRECHECK_DEDUPE`, `PRECHECK_TIMBLE` or `PRECHECK_STATUS`, with PII masked.

## Rules carried over from lendercore

| lendercore | L1 |
|---|---|
| `client_loan_id` = new random UUID on every precheck call | New id **per attempt** (`runNo`): UUIDv5 of `loanRequestId:r<runNo>:axis-dedupe`. A Temporal retry inside an attempt sends the same id; a new attempt sends a new one |
| Dedupe and Timble are sent the same request object | Timble rebuilds the identical body (same customer, same id); a test checks they are equal |
| A precheck exists and its status is not SUCCESS/FAILURE → skip, audit `skip` | `IN_PROGRESS` + the same audit row (only when the record belongs to another attempt) |
| A precheck exists and is complete → re-run, reset `responseMap` to `PENDING "Precheck re-initiated for the loanRequest"`, clear `rejectionReason` | Same, in `axis.precheck.saveDedupeRerun`; `reference_id` is also cleared until the new Timble reference arrives |
| Status polled only while the overall status is missing or `PENDING` | `ALREADY_FINAL` without calling Axis otherwise |
| Flags Y/WIP/N + Timble (SUCCESS → Y, missing → WIP, else N): all Y → SUCCESS, any N → FAILURE, any WIP → PENDING | Same decision and the same messages, including the failure detail JSON (null fields included). Tested for all 81 flag combinations × 5 Timble states |
| Timble callback: N in `name_match` or `aadhar_match` (trimmed, any case) → FAILURE with the message JSON in Java `HashMap` order | Same (`timble_error_message`, `aadhar_match`, `name_match`; only keys present) |
| Mongo field names from Spring Data | `clientLoanId`, `reference_id`, `responseMap`, `precheckStatus`, `approved`, `creation_date`, `createdAt`, `last_updated`, `version`, `_class`. Note: **`clientLoanId`**, not `client_loan_id`. The `@JsonProperty` on the entity only affects JSON |

## Where L1 deliberately differs

| # | lendercore | L1 | Why / impact |
|---|---|---|---|
| 1 | Dedupe and Timble in one call, one save | Two tasks, two saves | One lender side effect per task (P2). When a re-run's Timble fails, the precheck is already reset to PENDING (lendercore would have kept the old record) |
| 2 | Failed dedupe is logged and ignored | `REJECTED` (P17, confirm with product) | L2 decides; to behave like lendercore, it still runs Timble |
| 3 | Timble error: `reference_id: null` saved, precheck marked processed | `REJECTED`, nothing saved | In lendercore, polling would then stay `PENDING` forever; L2 can decide instead |
| 4 | Customer reused between dedupe and Timble | Timble fetches the customer from Core again | One extra Core GET; no PII kept in Temporal history |
| 5 | Redis lock (5 min) against concurrent prechecks | One workflow per loan request attempt (L2) + the `IN_PROGRESS` guard | Temporal's workflow-id uniqueness replaces the lock |
| 6 | Audit row created at start, updated to skip/processed/failure | One row written when the result is known (`skip` or `processed`); exceptions are not written (they are in Temporal history) | A crash between saving the Timble reference and writing the row loses that one row |
| 7 | Status polled when rupeekweb calls `GET /precheck` | L2 runs `axis.precheck.status` (on a timer, or synchronously from its GET endpoint) | Same writes; L2 picks when to poll |
| 8 | rupeekweb errors are logged and swallowed | `NOT_ACCEPTED` (4xx) or `L1_TECHNICAL` retries (429/5xx) | L2 must not fail the case on a notification error |
| 9 | Unknown flag / no precheck for a callback: exception (HTTP 500) | `L1_PERMANENT` / `NO_PRECHECK` | Clearer outcome, same data |

## Fixed in M6

- **Field name:** precheck documents written by L1 used `client_loan_id`; lendercore stores `clientLoanId`. Now
  `clientLoanId`.
- **`client_loan_id` per attempt:** a new id for every attempt (Q16 answered), instead of reusing the stored one.

## Local run

You can run every recipe on a local Temporal with stubbed Core, Axis and rupeekweb. See
[`l1-local-testing.md`](l1-local-testing.md):

```bash
dev/l1-local/l1-local.sh probe LR-OK-1
dev/l1-local/l1-local.sh run-recipe axis.precheck.timble '{"loanRequestId":"LR-OK-1","runNo":1,"loanProcess":"fresh"}'
dev/l1-local/l1-local.sh run-recipe axis.precheck.timbleCallback '{"event":{"customer_id":"CUST-OK","name_match":"Y","aadhar_match":"Y"}}'
dev/l1-local/l1-local.sh run-recipe axis.precheck.status '{"loanRequestId":"LR-OK-1"}'
dev/l1-local/l1-local.sh run-recipe precheck.notify.verified '{"loanRequestId":"LR-OK-1"}'
```
