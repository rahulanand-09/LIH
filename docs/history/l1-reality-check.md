# L1 reality check (after M6)

Status as of 2026-10-18, after milestones 1–6 and the dev reference workflow.

**Short answer.** The engine and tooling are solid and generic, and the Axis precheck works end to end with
lender-core parity. But for Axis the config came out about as long as the Java it replaces, and it's harder to
read in a few places. The re-architecture can solve the problem statement for most of LenderCore. Before
migrating more lenders, we should fix config readability and cover the integration types we don't support yet.

## 1. Size today

| What | Lines |
|---|---|
| L1 engine and tooling (Java, 107 files) | 7,811 |
| Tests | 4,737 (203 tests) |
| Config for 6 recipes, 22 operations, 4 resources | 1,405 JSON (1,006 of it Axis-specific) |
| Golden tests | 1,524 (32 cases) |
| Schemas, docs, dev harness | 261 / 1,453 / 675 |
| For comparison: all of lender-core | 112,292 Java (1,688 files) |

Where the engine code goes: publish 1.7k, ops 1.2k, engine 1.1k, expressions 1.0k, registry 0.8k,
interceptors 0.7k. That's a fixed cost. A second lender adds config only.

## 2. Is it easy to extend and maintain?

**What's good:**
- **No lender Java at all.** All 6 Axis recipes run on the generic engine, and adding a lender means adding
  config.
- **Behaviour is the same for every lender, for free:** retries and error classes, crash and rerun safety, audit
  rows, PII masking, version pinning, metrics and step-by-step logs.
- **Safety net:** schema checks, compile-time CEL checks, golden tests required for every recipe, parity tests
  against legacy, and publish refuses broken config. A bad change can't reach the registry.

**What M6 exposed:**

| Problem | Example | Fix |
|---|---|---|
| Config is as long as the Java it replaces | Axis is about 1,000 JSON lines; the legacy Axis precheck Java is roughly 700–900 lines | Expected. The win is publishing without a deploy, not fewer lines. Better authoring would shrink it (rows below) |
| Some CEL is code squeezed into strings | The status decision is one expression of about 1,500 characters; the callback logic is nested `cel.bind` calls | A `decide` step type (table of rules → value) and step-level `let`. Cap expressions at about 3 lines |
| A workaround is already there | `map.value` exists only to name a computed value | The same `let` above |
| Repeated steps | Dedupe and Timble both repeat core, existing, ids | Recipe fragments or includes |
| One Mongo operation per write shape | 7 save operations for one lender | Parameterised "set these fields" operations |
| Missing language features | No optional inputs (`loanProcess` is forced); JSON with no comments | Optional inputs; YAML or JSON5 authoring compiled to JSON |
| Error messages are hard for newcomers | A type error like `found no matching overload for _?_:_` | Friendlier compile messages with hints |
| Operation definition is still one flat type for all kinds | Flagged earlier | Split per kind before adding a 5th kind |

None of these block anything, but at 20+ processes they decide whether the config stays readable.

## 3. How far we are from the design and the goal

- **Design:** M1–M6 are built as designed. Two deviations were improvements (versions written in git, the
  `ResolveRecipes` activity), and one was a workaround (`map.value`).
- **Goal for Axis precheck** ("a lender change is a config publish"): achieved locally. It is not yet proven
  anywhere real.
- **Still missing for production:**
  - L2 adopting the activities;
  - QA Temporal and QA/prod registries;
  - CI secrets;
  - the live parity run;
  - a run against Axis UAT;
  - version retirement;
  - dashboards on the new metrics and logs.
- **Rough distance:** the Axis precheck to production is weeks, not months. Most of that is environment setup
  and L2, not L1 work.

## 4. Migrating the whole of LenderCore

lender-core has around 8 lenders (Axis, DCB, FED, ICICI, KVB, Liquiloans, SIB, …). Processes include precheck,
loan creation and sanction, document upload, KFS, fund transfer, callbacks, letters, the DCB loan lifecycle on
Zeebe, FED async over Camel/SQS, and EOD batch over SFTP.

How they fit today's model:

| Fits today | Needs a new operation kind | Probably stays Java (or a separate batch design) |
|---|---|---|
| REST + Mongo request/response steps, callbacks, status polling, notifications: most prechecks, sanction, status, notify | SOAP/XML (FED), message publish (SQS/SNS), encryption and signing, document rendering and upload (S3/PDF), Redis locks, a Java "code" escape hatch | EOD batch (large SFTP files, many rows); heavy document generation |

Rough estimate: about 70% of the integration logic fits the recipe model once the 5–7 missing operation kinds
exist. Batch processing needs its own design; it doesn't fit the "one case, small data" model.

The real cost is tracing each process, not writing config. For Axis, understanding lender-core plus proving
parity took more effort than the recipes themselves. Estimate 1–3 weeks per process including parity, and less
as the operation library grows. Reusable pieces already exist (Core fetch, precheck saves, audit rows,
rupeekweb notifications).

**Onboarding.** A newcomer has to learn:
- recipes, operations and resources;
- CEL;
- templates;
- golden tests;
- publishing;
- the outcome contract.

That's about a week with the current docs. The local harness (`precheck` plus the timeline and logs) makes the
model concrete quickly. What would make it smooth: a cookbook of patterns, a scaffold command (`new-recipe`),
editor validation using the schemas, and the readability fixes above. Without those, config turns into "code in
JSON" that only its authors can read, which is the main risk of this whole approach.

## 5. Does it solve the problem statement?

| Problem | Solved? |
|---|---|
| Lender logic hard-coded in Java; every change needs a deploy | **Yes** for request mapping, rules, persistence and notifications; still a deploy for a new operation kind or function (rare by design) |
| Inconsistent retries, error handling and audit across lenders | **Yes**: four error classes, one retry policy, the same audit and masking everywhere |
| Fragile long flows split across Camel, Zeebe and sync REST | **Yes**, through L2 on Temporal, as long as L2 adopts the graph model and outcome-only routing |
| Reruns and duplicate lender calls | **Yes**: stable ids, guards and crash tests |
| Slow onboarding of lenders and people | **Partly**: mechanically yes; readability work is still needed |
| Testing against legacy | **Yes**: the golden, parity and live-parity tooling has no equivalent in lender-core today |

**Migration path:** because L1 writes documents lender-core can read (same fields and statuses), we can migrate
one process at a time and run both systems side by side, switching per lender and process. That's the most
important property for a safe migration, and it's already proven for Axis.

## 6. Recommendations for the whole LenderCore re-architecture

1. **Ship Axis precheck to QA first** (L2 adoption, environments, live parity, Axis UAT). Until then, everything
   above is still theory.
2. **Spend about 2 weeks on authoring before lender #2:** the `decide` step and `let`, recipe fragments,
   optional inputs, YAML authoring, splitting the operation definition per kind, better error messages, and a
   cookbook.
3. **Make the second process deliberately different:** FED precheck (SOAP plus async messaging) or the DCB loan
   lifecycle. That tests whether new operation kinds slot in cleanly. Another REST lender would teach us little.
4. **Inventory LenderCore** into "fits / needs a new kind / stays Java", and decide the batch strategy early.
5. **Migrate process by process**, using shared Mongo and a per-lender switch, with live parity required before
   each switch.
6. **Governance:**
   - config review in pull requests;
   - golden coverage required;
   - the "code" escape hatch kept under about 10% of steps;
   - two roles: a platform team owning the engine and operation kinds, and integration authors owning the
     config.
7. **L1 and L2 alignment:** one expression language (CEL) in both layers, an agreed outcome vocabulary (Q4), and
   L2's graph model with park, resume and skip for `L1_UNKNOWN`.

**Bottom line:** the architecture is right, and the Axis precheck shows it works with real parity. Its success
at LenderCore scale depends on two things beyond the engine: config ergonomics, and process-by-process
migration with parity gates. Both are tractable if we treat them as first-class work rather than polish.
