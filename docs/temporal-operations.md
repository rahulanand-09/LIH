# Temporal — Setup and Operations

## What's configured today

- `WorkflowServiceStubs` target and namespace come from `LihProperties`
  (`lih.temporal-target`, default `localhost:7233`; `lih.namespace`, default
  `default`) — a single Temporal endpoint, no multi-cluster/multi-namespace
  handling yet.
- **Task queue per lender**: `"lender-{slug}"` (`TemporalConfig.taskQueue`).
  One `Worker` per configured lender (`lih.lenders`), all created by one
  shared `WorkerFactory` in this process.
- Same workflow (`FlowWorkflowImpl`) and the same activities implementation
  are registered on every lender's worker — there's no per-lender worker
  code, only per-lender config.
- Per-step activity options: `StartToCloseTimeout` from the step's
  `timeoutSec` (default 30s), `RetryOptions.maximumAttempts` from the
  step's `retries` (default 1, i.e. no retry — matches today's
  lender-core behavior of not retrying lender calls by default).
- Workflow id: `{lender}-precheck-{customerId}-{loanRequestId}`, started
  with `WorkflowIdReusePolicy.REJECT_DUPLICATE` — a duplicate start is
  rejected and treated as "already initiated" rather than erroring, giving
  idempotent case creation for free.
- Callback routing depends on a Temporal **visibility query**
  (`WorkflowId STARTS_WITH ... AND ExecutionStatus = 'Running'`) rather
  than an exact lookup — see `l2-lld.md` and `decision-log.md`.

## Open — network topology & low latency

Nothing below is decided yet; this is the working list for that upcoming
piece of work, not a design.

- Where the Temporal cluster (frontend/history/matching/persistence) sits
  relative to the LIH workers and relative to lendercore — same
  VPC/AZ/DC or not, and what that costs per round trip.
- Self-hosted Temporal vs. Temporal Cloud, and what that implies for
  latency and operational ownership.
- Whether the synchronous endpoints (`POST /precheck` waiting on `$ready`,
  `GET /precheck` firing a `status` Update) are latency-sensitive enough to
  need sticky execution tuning, worker-to-server locality, or local
  activities instead of regular activities for any step.
- Task-queue/worker-pool scaling strategy once lender count grows beyond a
  handful sharing one `WorkerFactory` — partitioning, dedicated pools per
  high-volume lender, etc.
- Timeouts/connection pooling in `RestAdapter`'s `HttpClient` relative to
  actual lender SLAs, once there's real traffic to measure against.
- Concrete latency targets to design against (today's lender-core baseline,
  if known, vs. what LIH needs to match or beat).
