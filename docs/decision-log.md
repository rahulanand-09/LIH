# Decision Log — L2-scoped

Open items carried forward from design review, filtered to what matters for
finishing L2. L1-specific items (CEL function set, operation kinds beyond
REST, config-publish approval, etc.) are intentionally left out — revisit
once L1 work starts.

## Blocking-shaped (affect whether Axis precheck actually works)

- **Callback correlation key.** The only field Axis's Timble callback
  carries that identifies the customer is `customer_id` — there's nothing
  unique per *run* (a customer can have more than one concurrent precheck,
  and a revert starts a fresh case for the same customer). Today's
  workaround (`LihPrecheckService.callback`) searches running workflow ids
  by `{lender}-precheck-{customerId}-` prefix, which only works because
  nothing stops two concurrent Axis cases for one customer from colliding
  on it today. Needs either a real correlation field from Axis, or an
  explicit "reject concurrent precheck for same customer" rule.
- **Source of truth.** Design review leans towards the workflow (not the
  Mongo `PrecheckEntity`) being authoritative, with Mongo as an idempotent
  projection. Today's `GET /precheck` reads `PrecheckEntity` directly and
  only reaches into the workflow when status is `PENDING` — the two can
  drift if a `PERSIST` step fails after the workflow has already moved on.

## Design gaps, not yet needed for the POC but will block the next flow

- **Outcome-label vocabulary** — free-form per-task labels vs. a fixed
  platform set (`OK`/`FAIL`/`PENDING` reserved for poll/notify semantics).
  Affects how `DECIDE` logic should be re-expressed once it moves into L1's
  outcome mapping (see `l2-lld.md` gap #2).
- **PARALLEL/JOIN semantics** — all/any/N-of-M join policy undecided, and
  not implemented at all yet. Needed for any flow with concurrent lender
  calls (e.g. Federal's multi-check pattern).
- **Version pinning** — no manifest-activity-style per-case snapshot of
  which L1/L2 config version a case started on. Fine while everything loads
  once at process startup; becomes necessary the moment config is published
  at runtime without a redeploy.
- **onError / PARK.** Only a flat retry count exists per `CALL`. No parked
  state, no operator resume/skip/fail action — anything that exhausts
  retries today just fails the activity (and the workflow surfaces it as a
  500 at start, or an Update failure at event-time).
- **POLL/DELAY node kind.** Every lender's status-check today is
  caller-driven (`GET /precheck` triggers a pull); nothing in L2 can poll
  on a timer by itself yet.
- **Batch/case-less flow kind.** Cron/EOD-style work (seen in other
  lenders' designs) has no home in the current per-case workflow model.

## Resolved / not worth re-litigating

- Expression language for L2: restricted SpEL via `Expr`, not CEL — treated
  as acceptable for now since L2's own guard/decide logic is meant to
  shrink (see gap #2), not grow. Revisit only if L1 settles on CEL and
  having two evaluators becomes awkward.
- Protocol scope: REST only for the POC; SOAP/SFTP are registered as named
  adapters that fail loudly rather than silently, so adding them later is a
  config-compatible change, not a breaking one.
