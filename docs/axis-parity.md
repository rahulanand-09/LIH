# Axis precheck: LenderCore vs LIH, step by step

Scope: the `axis-precheck-decomposed` flow (+ 7 subflows, tasks under `flow-config/tasks`) against LenderCore's
Axis precheck at `release-AWS-2.0` @ `8625f66fe`. Method: a code read-through of LenderCore, compared with the LIH
config; rows marked **tested** are pinned by `AxisParityTest` / `AxisParityFlowTest`, whose expected strings are
produced by replaying LenderCore's own logic (its Jackson-annotated `ResponseMap`, its `HashMap` ordering), not typed in.

LenderCore paths are relative to `lender-core/src/main/java/com/rupeek/lender/`. Abbreviations:
`APP` = `service/axis/AxisPrecheckProcessor.java`, `AAS` = `service/axis/AxisApiService.java`,
`AC` = `client/AxisClient.java`, `ATC` = `service/axis/AxisTimbleCallbackProcessor.java`,
`PS` = `service/PrecheckService.java`, `RW` = `service/rupeekWeb/RupeekWebServiceProvider.java`,
`AUD` = `audit/aspect/AxisApiAuditAspect.java`, `AAUD` = `audit/service/ApiAuditService.java`.

Verdicts: **MATCH** same behaviour; **FIXED** was a gap, closed in this change; **GAP** open; **DIFF** deliberate
difference, kept; **UNKNOWN** cannot be determined from code.

Config versions: every changed task/flow file was bumped to `"version": 2` (registry versions are immutable, so a
changed file under the same version would never be picked up by an already-seeded registry).

## Summary

65 steps compared: 31 MATCH, 13 FIXED, 5 GAP, 14 DIFF, 2 UNKNOWN.

### Fixed in this change (each with a test)

| Step | Gap | Effect before | Severity |
|---|---|---|---|
| 14 | `agreement_id` was a literal in JSON | **wrong Axis request** on any real run not matching the literal | high |
| 40 | status poll had no delay on `WIP` | hot loop against the real Axis API, history growth | high (ops) |
| 52 | a stored `FAILURE` ended the case **APPROVED** | **wrong decision** | high |
| 18 | activity timeout (30 s) < Axis read timeout (120 s) | Temporal retried an in-flight, non-idempotent POST: duplicate Axis dedupe/timble | high (ops) |
| 43, 56 | FAILURE message / profile JSON carried extra `null` members | **wrong DB state** (`responseMap`, audit remarks) and wrong text sent to rupeekweb | medium |
| 36 | timble-callback failure message listed absent keys as `null` | **wrong DB state** (`responseMap.timble.statusMessage`) | medium |
| 28 | re-run with an invalid dedupe wrote the timble reference and a `processed` audit row | **wrong DB state** (LenderCore aborts, writes only a `failure` audit row) | medium |
| 24 | `LenderApiAuditLog` rows had no `lenderSlug`/`loanRequestId`/`customerId` | audit rows unqueryable by LenderCore's audit API | medium |
| 29 | bare record lacked `precheckStatus: no_action` | wrong DB state (field absent) | low |
| 17 | `has(resp.body.data)` accepted `"data": null` | an invalid dedupe treated as valid | low |
| 12 | no `Accept` header | request differs trivially | low |
| 42 | flat flow used a diverged decision task | flat flow only (test oracle) | low |

### Still open

| Step | Gap | Severity |
|---|---|---|
| 25 | `LenderApiAuditLog.apiEndpoint` carries the full URL with query | cosmetic |
| 26 | no `changeHash`, no duplicate-state suppression, no `_class` on audit rows: every status poll writes a row | audit volume only |
| 50 | audit-outcome update picks the oldest `processed` row, LenderCore the newest | low |
| 6 | no Redis-style lock; needs the controller track to reject duplicate workflow ids | needs controller track |
| 39 | the callback's HTTP answer to Axis (`200`/`400` + `{"status":...}`) is not produced by the flow | needs controller track |

### UNKNOWN (needs a human)

1. Who polls `GET /precheck` for Axis in production and how often (outside this repo): decides the right `pollDelay` (step 40).
2. Which date-field set LenderCore persists on `LenderCustomerPrecheckAudit` (step 32); check one real document.
3. Whether the real `LenderCustomerPrecheck` collection carries the unique indexes `PrecheckEntity` declares on `loanRequestId` and `customerId` (`PrecheckEntity.java:21-25`). If `customerId` is unique, a second Axis precheck for the same customer fails on insert in both systems.
4. Whether Axis treats `null` and an absent body field differently. LenderCore's default-Jackson `RestTemplate` sends explicit `null`s (step 15); LIH sends them too.
5. `version` counters (step 53) and the qa/prod literals in `rupeekweb.json` / `core.json` (step 58).


## A. Trigger and customer data

| # | Step | LenderCore | LIH today | Verdict | Action |
|---|---|---|---|---|---|
| 1 | Trigger | `POST /precheck` `{loanreqID, lenderSlug, loanProcess}` (`controller/PrecheckController.java:44-48`) -> `PS:264-283`; processor bean = slug `axis` (`service/LenderLoanProcessorFactoryImpl.java:46-52`) | `POST /flows/axis-precheck-decomposed/start {inputs:{loanRequestId, runNo, loanProcess}}` (controller track); dev: `FlowTriggerClient` | DIFF | `runNo` is LIH-only (seeds `client_loan_id`, step 8): a re-run needs a new `runNo` |
| 2 | Response to caller | 200 `{message:"Precheck Initiated for loan Request ID: <id>", status:"SUCCESS"}` even when dedupe/timble were invalid on a fresh run (`PS:275-278`); an exception -> 500 | workflow end status (`SKIPPED`, `REJECTED`, `APPROVED`, ...) | DIFF | none |
| 3 | Core fetch | `Core.getCoreCustomerData("loanRequestId", id)` (`customeraccountcreation/client/Core.java:42-65`): `GET {core.coreUrl}api/support/fetchloandetailsv2?loanRequestIds=<id>&loanIds=&lpLoanIds=&uLoanIds=&uLpLoanIds=` (v2 for beta/qa/prod, v4 for uat: `application.properties:503,510,518,526`); headers `Accept: application/json`, `Authorization: <core.coreToken>`; `new RestTemplate()` (no timeouts) | `core.fetchCustomerByLoanRequest`: same path and query order; `Authorization` from `CORE_TOKEN`; `Accept` from resource headers; 10 s/30 s timeouts | MATCH | **tested** (exact URL, headers). `customerDataPath` is `...v2` in every env block; edit it for a uat-style core |
| 4 | Core validation | null/empty `loandata` -> HTTP 500 `"FAILURE"`, no side effects (`service/ClientHelperService.java:135-149`, `PS:267-269`) | success needs `loandata` non-empty, else PERMANENT -> the case PARKs for an operator | DIFF | no DB side effects either way |
| 5 | Customer fields read | `id, customerProofName, firstName, lastName, dob, gender, email, customerPhone, customerPanName, pan, aadhar, customerAddress, permanentAddress.{city,state,pin}, presentAddress.{city,state,pin}` (`AAS:1206-1304`; DTO `customeraccountcreation/dto/core/Customer.java`) | `map.axis.precheckRequest` reads the same keys | MATCH | none |

## B. Guards, audit row, identity

| # | Step | LenderCore | LIH today | Verdict | Action |
|---|---|---|---|---|---|
| 6 | Concurrency lock | Redis lock `AXIS-PRECHECK-<customerId>-<loanRequestId>`, 5 min (`APP:31,46-51`); on conflict audit `skip` / `"API call skipped - Concurrent request received, Request already in progress"`, return | none; one workflow per loan request plus step 7 | GAP | controller track: workflow id `axis-precheck-decomposed-<loanRequestId>`, reject a duplicate start |
| 7 | In-progress skip | existing document by `(loanRequestId, lenderSlug=axis)` whose `responseMap.axisPreCheckStatus.status` is not SUCCESS/FAILURE (case-insensitive) -> audit `skip` / `"API call skipped - Axis precheck already initiated and not yet completed"`, return (`APP:53-63`) | `axis.decomposed.dedupe.inProgress`: same condition **and** `clientLoanId != this run's id`; audit via `precheck.audit.insert` with identical status and remarks; END `SKIPPED` | MATCH | **tested** (audit row text). The extra clause (a replay of the same run is not 'another attempt') is a deliberate DIFF |
| 8 | `client_loan_id` | `UUID.randomUUID()` per invocation (`APP:78`) | UUIDv5 of `<loanRequestId>:r<runNo>:axis-dedupe` via `gen.stableId`, so Temporal retries resend the same id | DIFF | Axis receives a valid UUID either way |
| 9 | Initial audit row | row inserted at the start with a null status and updated at the end (`APP:45,119-136`) | row inserted only at an outcome (skip / processed / abort), same columns at the end | DIFF | a case that parks mid-way leaves no audit row |
| 10 | `loanProcess` | only on the audit row (`APP:123,126`); `PrecheckEntity.loanProcess` is never set for Axis (`APP:65-75`) | audit row only; `loan_process` never set | MATCH | note: LenderCore's `GET /precheck?loanProcess=X` finds no Axis document unless `X` is null |

## C. Dedupe call

| # | Step | LenderCore | LIH today | Verdict | Action |
|---|---|---|---|---|---|
| 11 | URL / method | `POST {axis.preCheckBaseurl}{axis.initiatePreCheck=/investor_integration/actions/invoke_api}?type=dedupe` (`AC:193-211`, `application.properties:749-751`) | `axis.precheck.invoke`: same path + `type=dedupe`, base from `AXIS_PRECHECK_BASEURL` | MATCH | **tested** |
| 12 | Headers | `Content-Type: application/json`, `API-key: <axis.preCheckApiKey>`; **no `Product-Id`** (`AC:199-201`; `createHeaders`, `AC:54-66`, is not used for precheck); RestTemplate adds `Accept: application/json, application/*+json` | resource `axis-precheck`: `Content-Type`, `Accept` (added), `API-key` from `AXIS_PRECHECK_API_KEY`; no `Product-Id` | FIXED | `Accept` added; **tested** (headers) |
| 13 | Auth | raw key in the `API-key` header | `API_KEY_HEADER`, header `API-key` | MATCH | none |
| 14 | `agreement_id` | `axis.preCheckAgreementId` (`AC:203`; per profile in `application-{qa,prod}.properties`) | was a literal in JSON (`local-agreement` / `2181` / `323`); now `${AXIS_PRECHECK_AGREEMENT_ID}` (local: required; qa/prod: defaults 2181/323 from LenderCore's profile files) | FIXED | set `AXIS_PRECHECK_AGREEMENT_ID`; **tested** (resolution, missing-variable failure, body) |
| 15 | Body fields | `AxisPrecheckRequest` JSON names; constants `model_name=CLM1`, `customer_type=individual` (`dto/axis/AxisPrecheckRequest.java`, `Constants.java:232-233`), `dedupe_type=single`, `application_id=null`, `dedupe_ids=[PAN, AADHAR]`, two addresses PERMANENT/COMMUNICATION with whitespace collapsed (`AAS:1233-1304`) | `map.axis.precheckRequest` | MATCH | **tested** (exact body, field by field) |
| 16 | Name split | split `customerProofName` on a single space and upper-case: 1 part -> last `"."`; 2 -> first/last; 3 -> first/middle/last; 4+ -> middle = part 2, last = the rest joined; blank -> raw `firstName`/`lastName`, not upper-cased, no middle (`AAS:1207-1231`) | `rk.nameSplit` | MATCH | **tested** (every arity) |
| 17 | Response validity | valid iff body non-null, `errors` empty and `data` non-null (`APP:142-143`); any HTTP error or exception is turned into a synthetic `errors` entry (`AC:213-221`), so the client never throws | success = `status==200 && errors empty && data != null` (was `has(data)`, which accepted `"data": null`); 4xx and 200-with-errors -> `BUSINESS` -> `REJECTED`; HTTP 500 -> `UNKNOWN` (no retry, PARK); 502/503/504 and other 5xx -> `TECHNICAL`, 5 attempts then PARK | FIXED | `data: null` closed. 5xx is a deliberate DIFF: LenderCore carries on and persists a bare record, LIH retries and parks |
| 18 | Timeouts | `restTemplate` 120 s connect/read (`helpers/RestConfiguration.java:91-100`) | resource 120 s/120 s, but the **activity timeout was 30 s**, so Temporal retried a POST that was still in flight | FIXED | engine: `FlowWorkflowImpl.TASK_START_TO_CLOSE` = 150 s; `AxisParityTest.everyHttpResourceTimesOutBeforeTheActivityDoes` |
| 19 | Output used | `data.applicantId` (`APP:145`) | `out.applicantId` | MATCH | none |

## D. Timble call and ordering

| # | Step | LenderCore | LIH today | Verdict | Action |
|---|---|---|---|---|---|
| 20 | Request | same URL, headers and body as dedupe with `type=dedupe_timble`; same request object so the same `client_loan_id` (`AC:224-254`, `AAS:1246-1260`) | `axis.precheck.timble`, same `call_request` body and id | MATCH | **tested** |
| 21 | Ordering | dedupe then timble, always, back to back, even when dedupe was invalid (`APP:84-89`) | dedupe `REJECTED` -> `timble` (top-level edge) | MATCH | **tested** for a fresh run; see the re-run row for the one exception |
| 22 | Timble response | any non-null response is accepted; `reference_id` is stored (null when the body carried `errors`); never aborts (`APP:159-169`, `AC:245-253`) | `success` = `200 && errors empty`; failure -> `REJECTED`, the case ends, nothing is written | DIFF | persisting a null reference and then waiting for a callback that cannot arrive (LenderCore leaves the record `PENDING` forever, timble flag `WIP`) is not worth reproducing; a human decision if exact parity is wanted |
| 23 | Reference id | `reference_id` (`APP:163`; `@Field("reference_id")`, `PrecheckEntity.java:61`) | `reference_id` | MATCH | none |

## E. Persistence after initiate

| # | Step | LenderCore | LIH today | Verdict | Action |
|---|---|---|---|---|---|
| 24 | `LenderApiAuditLog` identity | `lenderSlug=axis`, `customerId`, `loanRequestId` from `ApiAuditContext` (`APP:81`, `AUD:101-103`) | the three tasks had no `lender`/`auditKeys`, so all three were null; now declared, and the flows pass `loanRequestId`/`customerId` | FIXED | **tested** (all three request types) |
| 25 | `apiEndpoint` | `base+path`; `?type=dedupe_timble` / `?type=dedupe_status` only for those two (`AUD:140-153`) | full URL including the query for all three | GAP | cosmetic; not changed |
| 26 | Audit row shape and de-duplication | `requestType` `PRECHECK_DEDUPE/TIMBLE/STATUS`; raw payloads truncated at 10000 + `"... [TRUNCATED]"`; `responseCode` string; status SUCCESS/BUSINESS_FAILURE/TECHNICAL_FAILURE/TIMEOUT; `changeHash` (sha-256); an identical consecutive state is skipped (`AUD:125-200`, `AAUD:47-146,200-205`) | same `requestType` strings, truncation, `responseCode` and status mapping; PII masked (see the PII row); **no `changeHash`, no suppression, no `_class`** | GAP | audit only: every status poll writes a row; needs `TaskAuditor`/`MongoAuditWriter` support |
| 27 | Fresh record | one `save` after both calls: new `PrecheckEntity{loanRequestId, customerId, lenderSlug=axis, creation_date, responseMap={}, precheckStatus=processed, applicantId, approved=false, clientLoanId, last_updated, reference_id}` (`APP:65-75,103-105,138-169`) | `saveDedupe` UPSERT on `(loanRequestId, lenderSlug)` then `saveTimbleRef` UPSERT; same fields and names; extra `createdAt`, `_class`, `version` | MATCH | **tested** on fields. Two writes instead of one; `version` differs (see the `version` row) |
| 28 | Re-run | existing record (completed): both calls are made; dedupe null/errors/no data -> `IllegalStateException`, audit `failure` with that message, **nothing written** (`APP:91-96,110-113`); else `responseMap` replaced by `{axisPreCheckStatus:{PENDING,"Precheck re-initiated for the loanRequest"}}`, `rejectionReason=null`, ids and `reference_id` overwritten (`APP:97-100`) | valid: `saveDedupeRerun` (same writes; `reference_id` nulled then set by the timble save). Invalid: **new `ABORTED` branch**: audit row `failure` / `"Axis precheck re-run aborted, invalid dedupe or timble response for loanRequestId <id>"`, no precheck write, no timble call, END `REJECTED` | FIXED | **tested**. DIFF: LenderCore still makes the timble call and discards it; LIH skips it (it would also raise an orphan Timble callback) |
| 29 | Fresh run with an invalid dedupe | saves a bare record: no `applicantId`/`clientLoanId`, `precheckStatus=no_action` (builder default, `PrecheckEntity.java:27-29`), plus `reference_id` | `saveTimbleRef` `setOnInsert` now includes `precheckStatus: no_action` | FIXED | **tested** |
| 30 | `processed` audit row | `precheckStatus=processed`, remarks `Axis precheck initiated. applicantId=%s, clientLoanId=%s, timbleReferenceId=%s` from the saved entity (`APP:106-107`) | `axis.decomposed.timble.auditRemarks`, same text, `null` rendered as `null` | MATCH | `clientLoanId` differs only in the dedupe-invalid case (LenderCore: the entity's, i.e. null or old) |
| 31 | Failure audit | any exception -> row `failure` + message, rethrown -> 500 (`APP:110-113`) | the node retries then PARKs; no audit row | DIFF | operator resume replaces the 500 |
| 32 | Audit columns | `loanRequestId, customerId, lenderSlug, loanProcess, responseMap, precheckStatus, remarks, createdOn, lastUpdatedOn` plus `AbstractEntity` (`creation_date, last_updated, createdAt, version`) (`PrecheckAuditEntity.java:11-39`, `AbstractEntity.java:18-55`) | all written, both date-field sets | UNKNOWN | LenderCore re-declares `createdOn`/`lastUpdatedOn` in the subclass; which set Spring Data persists is not determinable by reading. Check one real document |

## F. Timble callback

| # | Step | LenderCore | LIH today | Verdict | Action |
|---|---|---|---|---|---|
| 33 | Intake | `POST /v1/callback?source=YUBI&purpose=TIMBLE_STATUS` (`controller/CallbackController.java`); bean `YUBI_TIMBLE_STATUS` = `source + "_" + purpose` with `'` stripped (`LenderLoanProcessorFactoryImpl.java:35-43`) | Temporal signal `timbleCallback` on the workflow; the HTTP surface is the controller track's | DIFF | controller track should keep this URL and params so the Axis-side webhook need not change |
| 34 | Fields | keeps `product_id, customer_id, timble_status, name_match, aadhar_match, timble_error_message` (`Constants.java:164`, `ATC:42-45`) | `decide` reads `customer_id, name_match, aadhar_match, timble_error_message` | MATCH | none |
| 35 | Failure rule | `trim()`, `equalsIgnoreCase("N")` on `name_match` or `aadhar_match`; an absent value counts as not-N (`ATC:48-55`) | `.trim().upperAscii() == 'N'` | MATCH | **tested** (`n`, ` N `, absent) |
| 36 | Messages | success `"Timble Precheck Successful"`; failure `"Timble Precheck Failure : " + JSON(HashMap of the PRESENT keys among name_match, aadhar_match, timble_error_message)`; HashMap iteration order is `timble_error_message, aadhar_match, name_match` for every subset (`ATC:57-75`) | `decide` + new `rk.compact`: absent keys were previously emitted as `null` | FIXED | **tested** against a replay of LenderCore's HashMap. Edge: LenderCore NPEs (`Collectors.toMap`) on a present-but-`null` value; LIH treats it as absent |
| 37 | Record lookup | `findByCustomerIdAndLenderSlugOrderByCreatedOnDesc(customer_id, axis).get(0)` (`ATC:52`); empty list -> `IndexOutOfBounds` -> 500 | `precheck.findLatestByCustomer` sort `creation_date -1`; none -> `NO_PRECHECK` -> END `REJECTED` | DIFF | none |
| 38 | Write | `responseMap.timble={status: SUCCESS|FAILURE, statusMessage}`, `lastUpdatedOn`, entity save (`ATC:78-92`) | `$set responseMap.timble`, `last_updated`, `$inc version` | MATCH | none |
| 39 | Response to Axis | `200 {"status":"SUCCESS"}` or `400 {"status":"FAILURE"}` when validation failed (`customeraccountcreation/service/CommonService.java:634-643`) | not produced by the flow (it exposes `decide.outputs.ok`) | GAP | controller track |

## G. Status

| # | Step | LenderCore | LIH today | Verdict | Action |
|---|---|---|---|---|---|
| 40 | Trigger / cadence | no loop: each `GET /precheck` (`PrecheckController.java:50-54`, `PS:285-399`) makes at most one status call, and only if `axisPreCheckStatus` is absent or `PENDING`; the cadence is the caller's (outside this repo) | loops after the callback; **it re-polled immediately** (a hot loop against the real API, flagged in `AxisPrecheckFlowTest`'s javadoc) | FIXED | durable 60 s timer: `pollDelay` = `WAIT` on a never-signalled event, `timeout: PT60S`, `onTimeoutOutcome: POLL`; **tested** (virtual clock advances >= 60 s). Count unbounded, bounded by the child workflow's 1-day timeout. Tune in `axis-precheck-decomposed-status.json`; real cadence UNKNOWN |
| 41 | Request | `POST ...?type=dedupe_status`, body `{agreement_id, customer_id=entity.customerId, application_id=entity.applicantId, client_loan_id=entity.clientLoanId, model_name=CLM1, customer_type=individual, others null}` (`AAS:1306-1311`, `AC:257-281`) | `map.axis.precheckStatusRequest` from the stored document; `axis.precheck.status` | MATCH | **tested** (exact body, headers as dedupe) |
| 42 | Flat flow decision task | n/a | `axis-precheck.json` (flat, test oracle) used `axis.precheck.statusDecision`, which received the whole document as `timble` (wrong shape); it now uses `axis.decomposed.status.decision`. The old task file is unreferenced but kept (config reduction is out of scope) | FIXED | none |
| 43 | FAILURE message | `"Precheck failure received from lender services : " + JSON(ResponseMap{dedupe,pan,ramp,hunter,timble})`; class-level Jackson `NON_EMPTY` (`ResponseMap.java:6-8`) omits null members; order `dedupe,pan,ramp,hunter,timble` (`PS:341-356`) | `axis.decomposed.status.decision` embedded `customerAcceptanceStatus/customerBlacklistStatus/axisPreCheckStatus: null`; now `rk.compact` | FIXED | **tested**: byte-identical to a replica of `ResponseMap`, with and without a timble entry |
| 44 | Response parse | `AxisPreCheckStatusApiResponse{dedupe,pan,ramp,hunter}`; `AxisPreCheckStatus.valueOf(...)` is case-sensitive `Y/WIP/N`; null/unknown -> exception -> 500, nothing persisted (`PS:326-329`); an HTTP error yields an empty response -> the same exception | success needs `200` and all four in `{Y,WIP,N}`; 429/5xx -> `TECHNICAL` (5 attempts then PARK); else `PERMANENT` -> PARK | MATCH | no state change on a bad response; DIFF: parks instead of a 500 |
| 45 | Timble flag | `SUCCESS` -> `Y`; no timble entry -> `WIP`; else `N` (`PS:311-321`) | `axis.decomposed.status.timbleFlag` | MATCH | **tested** |
| 46 | Decision | all five `Y` -> SUCCESS; else any `N` -> FAILURE; else PENDING (`PS:331-369`) | `axis.decomposed.status.decision` | MATCH | **tested** (an `N` beats a `WIP`) |
| 47 | Per-flag status | `dedupe/pan/ramp/hunter = {status: SUCCESS|PENDING|FAILURE, statusMessage: ""}` (`PS:326-329`, `AxisPreCheckStatus.java:4`) | same | MATCH | **tested** |
| 48 | PENDING writes | `axisPreCheckStatus={PENDING,"One of the services is giving WIP"}` plus per-flag statuses saved; `approved` untouched (`PS:362-366,374`) | `axis.precheck.saveStatusPending` | MATCH | **tested** (message) |
| 49 | SUCCESS writes | `approved=true`; `axisPreCheckStatus={SUCCESS,"PreCheck success for the loanRequest"}`; the newest `processed` audit row -> `success`, remarks = message, `responseMap` = the whole map, `lastUpdatedOn` (`PS:331-337,655-668`); `precheckStatus` stays `processed` | `saveFinal` then `recordOutcome` | MATCH | **tested** (document and audit row) |
| 50 | Which audit row is updated | `findFirst...OrderByCreatedOnDesc` among `processed` rows: the newest (`PS:657`, `repositories/common/PrecheckAuditRepository.java:10`) | `UPDATE_ONE` on `(loanRequestId, axis, processed)`: the first match, in natural order | GAP | low: only matters if an older run left a row at `processed`; `UPDATE_ONE` has no sort |
| 51 | FAILURE writes | `approved=false`, message above, entity saved, audit row -> `failure`, then the rupeekweb failure notify (`PS:338-361`) | `saveFinal` + `recordOutcome` + `notifyFail` | MATCH | LIH persists first, LenderCore notifies before its last save (DIFF, safer) |
| 52 | Already final | status SUCCESS/FAILURE -> no Axis call, the stored record is returned (`PS:304-307`) | SUCCESS -> `ALREADY_FINAL` -> APPROVED; **FAILURE was also `ALREADY_FINAL` -> APPROVED**; now `ALREADY_FAILED` -> REJECTED; no notify either way | FIXED | **tested** (task outcomes and an end-to-end case) |
| 53 | `version` | `@Version int` (`AbstractEntity.java:28-29`), +1 per save | `$inc version 1` per write; the count differs (LenderCore saves once per phase) | UNKNOWN | primitive initial-value semantics not determinable; values will not diff equal |
| 54 | No record | `GET` returns a null body with 200 (`PS:290-293`) | `NO_PRECHECK` -> REJECTED | DIFF | none |

## H. rupeekweb notifications

| # | Step | LenderCore | LIH today | Verdict | Action |
|---|---|---|---|---|---|
| 55 | Success notify | `POST {rupeekweb.endpoint}api/loanrequest/precheck-verification`, `application/x-www-form-urlencoded` `loanrequestId=<id>&approved=true`, header `Authorization: <adminToken>` (`RW:53-72`, `application.properties:539-545`) | `rupeekweb.precheck.verification`: `bodyEncoding FORM`, same keys and values | MATCH | **tested** (exact body, headers) |
| 56 | Failure notify | `POST api/lender/updateprecheckfailure` JSON `{loanRequestId, lenderSlug:"axis", lendercustomerprofile:<JSON string of the PrecheckEntity via a plain ObjectMapper>}` (`RW:83-112`); `@JsonIgnore`d audit fields absent, `client_loan_id` per its `@JsonProperty`, `ResponseMap` nulls omitted | `rupeekweb.precheck.failure` + `buildProfileJson`: key order = `PrecheckEntity` declaration order, `id` as a hex string, `rk.compact` on `responseMap` (nulls were previously emitted) | FIXED | **tested** (key order, omissions, shape). LIH serialises the persisted document, LenderCore its in-memory entity just before its last save: same content |
| 57 | Notify failure behaviour | all exceptions swallowed, no retry (`RW:78-80,109-111`) | 4xx -> `NOT_ACCEPTED`, continue; 429/5xx -> 3 attempts then PARK | DIFF | a lost notification is visible, not silent |
| 58 | Base URL | `rupeekweb.endpoint=${RUPEEKWEB_ENDPOINT}` with relative paths without a leading `/` (`application.properties:539-545`) | `RUPEEKWEB_BASEURL` + `/path`, slash-normalised | MATCH | the qa/prod literals in `rupeekweb.json` could not be verified from code; the `local` env block uses the env var |

## I. Data model

| # | Step | LenderCore | LIH today | Verdict | Action |
|---|---|---|---|---|---|
| 59 | Collections | `LenderCustomerPrecheck` (`PrecheckEntity.java:18`), `LenderCustomerPrecheckAudit` (`PrecheckAuditEntity.java:13`), `LenderApiAuditLog` (`audit/entity/LenderApiAuditLog.java:26`) | same names; allowlist in `lendercore-mongo.json` | MATCH | none |
| 60 | Precheck fields | `loanRequestId, customerId, precheckStatus (enum name), lenderSlug, lenderCustomerId, rejectionReason, responseMap, approved, retry, overallstpstatus, unsecurestpstatus, stpstatus, lenderstpstatus, autofundtransferstatus, applicantId, clientLoanId, loan_process, reference_id`; base `_id, version, created_by, creation_date, last_updated_by, last_updated, createdAt` | writes `loanRequestId, customerId, lenderSlug, precheckStatus, applicantId, clientLoanId, approved, reference_id, responseMap.*, rejectionReason (null on re-run), creation_date, createdAt, last_updated, version, _class` | MATCH | every field LIH writes exists in LenderCore under the same name |
| 61 | Status strings | `PrecheckStatus`: `initiated, no_action, processed, success, failure, acceptance, skip, rejected` (`constants/PrecheckStatus.java`); `AxisPreCheckStatus` `Y/WIP/N` -> `SUCCESS/PENDING/FAILURE` | `processed`, `no_action`; audit `processed, skip, success, failure`; `SUCCESS/PENDING/FAILURE` | MATCH | none |
| 62 | `responseMap` shape | `axisPreCheckStatus, dedupe, pan, ramp, hunter, timble` each `{status, statusMessage}` (`ResponseMap.java`, `ResponseMapStatus.java`) | same | MATCH | **tested** |

## J. Deliberate differences

| # | Step | LenderCore | LIH today | Verdict | Action |
|---|---|---|---|---|---|
| 63 | PII masking in `LenderApiAuditLog` | payloads stored unmasked (`AUD:225-247`) | `aadhar_number`, `dedupe_ids[*].id_value`, `mobile_number`, `email`, both dates of birth and `address[*].address_value` are masked (`piiPaths` on the two POST tasks) | DIFF | a byte diff of those two payloads against LenderCore will differ; clear `piiPaths` locally if an exact diff is needed |
| 64 | Run identity | no run number; a re-run is 'existing record, completed' | re-triggering the same `(loanRequestId, runNo)` after completion is a no-op by design (stable `client_loan_id`) | DIFF | use the next `runNo` |
| 65 | Waiting for the callback | never waits | waits in the workflow (no timeout) for the Timble callback, then drives status polling itself | DIFF | none |
