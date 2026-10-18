# Decision Log — L1-scoped

Companion to [`l1-lld.md`](l1-lld.md). Status as of 2026-10-18.
**Decided** = build against it. **Proposed** = default for the POC, open to
review. **Open** = needs an answer; the default used until then is noted.

## Decided

| # | Decision | Why |
|---|---|---|
| D1 | L1/L2 split: L2 runs the flow on Temporal and routes only on outcome labels; L1 runs one lender step | The one fixed boundary |
| D2 | POC = Axis precheck end to end, starting with Axis Dedupe | Smallest real flow with a callback and a status poll |
| D3 | L1 is a config-driven worker; no lender-specific Java | Lender change = config publish, not a deploy |
| D4 | CEL (`dev.cel:cel`) is the expression language for all L1 config | Safe (no I/O, no Java access), always terminates, compile-time checks, deterministic |
| D5 | New code lives in package `com.rupeek.l1`, separate from existing `com.rupeek.lih` | Clean start; reconcile with L2 later |
| D6 | Java 21, Spring Boot 3.5.16, Temporal Java SDK 1.40.0 | Decided and applied to `pom.xml` 2026-10-17 |
| D7 | Expressions only see plain data (maps, lists, scalars, timestamps); CEL native types off | Avoids the SpEL-style reflection risk |

## Proposed (POC defaults)

| # | Proposal | Notes |
|---|---|---|
| P1 | Three config levels: recipe, operation definition, resource | Recipes/ops immutable + versioned; resources live |
| P2 | One activity = at most one lender side effect; recipes are linear | Waits, loops and branching between lender calls stay in L2 |
| P3 | One generic activity `RunTask(TaskRequest) → TaskResult` | Same for callbacks (mapping recipes) |
| P4 | Four failure classes → `L1_TECHNICAL` (retryable), `L1_BUSINESS`, `L1_UNKNOWN`, `L1_PERMANENT` | Timed-out non-idempotent call = `UNKNOWN` |
| P5 | POC operation kinds: `HTTP`, `MONGO`, `MAP`, `GEN` | `AUTH`, `CRYPTO`, `FILE`, `PUBLISH`, `RENDER`, `LOCK`, `CODE`, SOAP later |
| P6 | Executors do I/O only; all CEL evaluation, checks and classification in the engine | Uniform behaviour across kinds |
| P7 | Templates: `=` prefix for CEL leaves; `$each` / `$let` for nested lists | Keeps large payloads readable |
| P8 | Tier rule: CEL → `rk.*` → `CODE` op → operation kind | Health signals: `CODE` share < 10%, expression cap, 100% golden coverage |
| P9 | `rk.*` POC set: `round`, `sum`, `groupSum`, `groupCount`, `lookup`, `json`, `parseJson`, `date.parse`, `date.format`, `mask`, `nameSplit`, `uuidFrom` | Library versioned; functions only added or fixed |
| P10 | Audit as an interceptor writing the existing `LenderApiAuditLog` | Only existing `ApiRequestType` values until lendercore adds more |
| P11 | Config in git → CI validate + golden tests → publish to Mongo `lih_config`; versions immutable, pinned per case | Classpath loading allowed until M4 |
| P12 | No step journal; recipes written rerun-safe (check, reuse ids, upsert before call) | Journal only if a recipe cannot be made rerun-safe |
| P13 | Output guard: declared outputs only, ≤ 16 KB, no PII values | PII never reaches Temporal history |
| P14 | HTTP via JDK `HttpClient`, one per resource | Keeps "not sent" vs "sent, no reply" distinct for failure classification; `RestClient` optional on Boot 3 |
| P15 | Single task queue `lih-l1` for the POC | Per-lender queues later if needed |
| P16 | Secrets from environment variables behind `SecretResolver` | Vault later |
| P17 | Axis Dedupe: a failed dedupe returns `REJECTED` instead of being logged and ignored (legacy behaviour) | Behaviour change — confirm with product |
| P18 | Versions are declared in git (missing = 1), immutable once published, and only move forward (gaps allowed) | Replaces "assigned at publish": a recipe's `op@N` pin must be readable in review; gaps let prod skip versions only QA saw |
| P19 | Registry stores specs as JSON strings; "same content" = SHA-256 of the parsed, key-sorted document without `version` | `$each`/dotted keys are not valid Mongo field names; reformatting is not a change |
| P20 | Every recipe's highest version needs ≥ 1 golden test, or publish fails | The LLD's 100% golden coverage health signal, enforced |
| P21 | Redirects apply to recipes only; newest wins; a redirect to the same version undoes | Operations are pinned inside immutable recipes |
| P22 | CI: PR → `mvn test` + `validate --env qa`; merge → publish QA; manual → publish prod (GitHub environment `l1-prod` with reviewers) | Answers the "how" of Q8 for the POC; who approves prod is still open |

## Open

| # | Question | Default until answered |
|---|---|---|
| Q1 | Temporal cluster and namespace; who runs it | `localhost:7233`, `default` for dev |
| Q2 | Secrets store for QA/prod | Environment variables |
| Q3 | Can LIH connect to the lendercore Mongo cluster, and with which credentials? | Local Mongo for dev |
| Q4 | Outcome labels: free per recipe or a fixed platform set? (with L2) | Free per recipe, declared, checked at publish |
| Q5 | Does Axis treat a repeated `client_loan_id` as the same application? | Not idempotent → timeout after send is `UNKNOWN` |
| Q6 | Can new `requestType` values be written to `LenderApiAuditLog`? | Existing enum values only |
| Q7 | Which Timble callback field identifies the run? (with L2) | None known; correlation is L2's concern |
| Q8 | Config repo location and who approves a prod publish | `src/main/resources/l1-config` in this repo for the POC |
| Q9 | Axis precheck base URL per environment (not in lendercore repo) | `AXIS_PRECHECK_BASEURL` env var |
| Q10 | How does L2 call `RegistryService.resolve` — library or API? | Library, in-process |
| Q11 | Is ~3 lines the right tier-1 limit, enforced at publish? | Expression size cap only |
| Q12 | `$each`/`$let` in templates, or nested CEL `map()` only? | Support `$each`/`$let` |
| Q13 | Use Java 21 virtual threads for activity execution? | Platform threads until evaluated in M3 |
| Q14 | Where do the QA and prod `lih_config` registries live, and who holds `L1_CONFIG_MONGO_URI_QA/PROD`? | Local Mongo (`dev/l1-local`) |
| Q15 | Retiring old versions: when and how (needs "no open case pins it" from Temporal visibility)? | Never retired; all stored versions compiled at start-up |

## Rejected alternatives

| Option | Why not |
|---|---|
| SpEL | Spring's docs: untrusted expressions are dangerous "regardless of which EvaluationContext implementation is used"; reflective; no compile-time checks (CVE-2022-22963 class of bug) |
| MVEL / Groovy / JavaScript | Full languages: arbitrary code, infinite loops |
| JEXL | Reflective over Java objects; no type check; no termination guarantee |
| JSONata / jq as the main DSL | Recursion, no type check; JSONata may be added later for heavy transforms only |
| JSON Logic / own predicate language | Too weak; every new need becomes an operator or Java |
| Java transformer per lender | Every request change = deploy; defeats config-driven onboarding |
| One activity per operation | PII in Temporal history; history bloat; more L2 complexity |
