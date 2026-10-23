# LIH — High-Level Design

## Problem

Every lender integration in lendercore is hand-written Java: request
building, response extraction, status polling, callback handling, retries —
all duplicated per lender with their own bugs. Adding a lender or changing
one lender's API means a code change and redeploy.

## Direction

LIH replaces that with **one generic interpreter running lender flows from
published config, on Temporal**. The design settles on a fixed boundary
between two layers:

- **L2 (flow)** — the shape of a case: which steps exist, what order they
  run in, where callbacks/status-pulls plug in. L2 never looks at lender
  data directly to decide what happens next — only at outcomes a step
  produces.
- **L1 (task)** — everything lender-specific for one step: endpoint,
  protocol, auth, how to build the request, how to read the response, how a
  response maps to an outcome. A lender API change is an L1 config publish;
  a flow-shape change is an L2 edit.

This keeps lender-specific code (or eventually lender-specific config) from
leaking into the orchestration layer, and keeps the orchestration layer
generic enough that one interpreter runs every lender's flow.

Temporal was chosen over BPMN/Zeebe for the flow runtime — it gives durable
execution, timers, and synchronous request/response into a running case
(via Updates) without hand-rolled persistence or a separate async-service +
queue layer.

## Current build order

L2 is being designed, built, and tested first, as a stand-alone concern,
before L1 specifics (config schema, CEL DSL, operation kinds) are worked.
The L1 pieces in this repo today (`OperationDef`, `L1`, `L1Registry`) are
deliberately a thin stub — just enough for L2 to have something to call —
not the target L1 design.

## Service shape, now vs. later

Today LIH is **one tightly-coupled Spring Boot service**: the L2 engine
(Temporal workflow + activities), the L1 stub, and the HTTP surface
(`ApiController`, `LihPrecheckService`) all live in this one deployable,
calling each other in-process.

The intended path is to **split L2 out into its own standalone service**
once it's proven out — L2 is the layer that talks to Temporal directly, so
it's the natural seam: a future L1/worker service (or lendercore itself)
would call into L2 over a network boundary instead of a Java method call.
This repo's current coupling is a deliberate POC shortcut, not the target
architecture — don't read permanence into today's package layout.

## First use case

**Axis precheck** — the smallest real flow (dedupe call, Timble call,
Timble callback, status pull, decision, notify) — is the proof case, built
end-to-end in this repo (`flows/axis-precheck.json`, `l1/axis.json`).

## Rollout model

Once proven, rollout is a traffic-diversion slice: new cases for a lender
start on LIH, in-flight cases for that lender stay on the old lendercore
path until they complete. This avoids needing to migrate in-flight workflow
state between the two systems.
