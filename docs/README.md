# LIH — Design Docs

LIH (Lender Integration Hub) is a ground-up, config-driven replacement for
lendercore's hand-written per-lender Java. A generic interpreter runs each
lender's flow from published config on Temporal, so onboarding a lender or
changing its API becomes a config change, not a redeploy.

Current focus is **L2** (the flow layer): design it, build it, test it,
before any L1 (per-lender task config) work starts. These docs reflect that —
L1 is referenced only where needed to explain L2's boundary.

- [`hld.md`](hld.md) — what LIH is, the L1/L2 split, why Temporal, how the
  service is expected to evolve.
- [`l2-lld.md`](l2-lld.md) — low-level design of L2: the current POC
  implementation in this repo, and where it still falls short of the target
  flow-config spec.
- [`temporal-operations.md`](temporal-operations.md) — how this POC talks to
  Temporal today, and the open operational questions (topology, latency)
  still to be answered.
- [`decision-log.md`](decision-log.md) — L2-scoped open decisions and known
  gaps carried forward from design review, to work through before/while
  improving L2.
