# LIH — Design Docs

> `flow-lld.md` is the only current doc; everything under `history/` is design history.

LIH (Lender Integration Hub) is a ground-up, config-driven replacement for
lendercore's hand-written per-lender Java. A generic interpreter runs each
lender's flow from published config on Temporal, so onboarding a lender or
changing its API becomes a config change, not a redeploy.

**Current implementation: [`flow-lld.md`](flow-lld.md).** The L1/L2 two-layer
split described below was scrapped in favour of one recursive flow engine,
`com.rupeek.flow` — read that doc first for what's actually built and
running. `com.rupeek.lender.*`/`com.rupeek.lih.*` (the old linear-step-list L2
stub this superseded) have been deleted from the repo.

The docs below are the design history that got here — L1/L2 boundary
reasoning, why Temporal, the target flow-config DAG as it was first proposed —
kept for context, not as a description of the current code:

- [`design.md`](history/design.md) — single consolidated overview: L1/L2 boundary,
  the (now superseded) L2 implementation, the target flow-config DAG, the
  pluggable `TaskExecutor` model, library/hosted-service deployment, namespace
  multi-tenancy, and the open decision log, all in one doc.
- [`hld.md`](history/hld.md) — what LIH is, the L1/L2 split, why Temporal, how the
  service is expected to evolve.
- [`l2-lld.md`](history/l2-lld.md) — low-level design of the old L2 stub, and where it
  fell short of the target flow-config spec.
- [`temporal-operations.md`](history/temporal-operations.md) — how this POC talks to
  Temporal today, and the open operational questions (topology, latency)
  still to be answered.
- [`decision-log.md`](history/decision-log.md) — L2-scoped open decisions and known
  gaps carried forward from design review; several are now resolved in
  `flow-lld.md` (version pinning, cycle/depth, PARK semantics) rather than
  still open.
