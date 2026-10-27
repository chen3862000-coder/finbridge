# ADR-006: Hot Swap With Invocation Pinning and Draining

- **Status:** Accepted for M5 implementation
- **Date:** 2026-10-27

## Context

Hot activation and routing changes must not change the implementation midway through an invocation or unload a class loader while provider code is executing. Ordinary payment calls and historical status reconciliation have different eligibility needs.

## Decision

Acquire an atomic invocation lease against an eligible plugin handle before provider code runs. Routing snapshots select handles for new calls; a binding update does not mutate held leases. Drain closes normal acquisition, allows only ownership-based reconciliation where needed, waits a bounded duration, and unload is refused while leases, bindings, or unresolved owned transactions remain.

M5 persists approved artifact metadata and desired lifecycle state, immutable artifact checksum identity, tenant capability bindings, and append-only lifecycle audit events in PostgreSQL. Artifacts remain local, prebuilt, allowlisted files; arbitrary upload and signing are not implemented. Startup reloads only persisted active artifacts after checksum verification, then restores bindings. Upgrade/rollback changes future bindings only; old versions remain loaded and retained until no binding, lease, or unresolved payment owner references them. Drain/unload outcomes and failures are durably audited. In-memory handle state is reconstructed after restart and is never treated as durable actual state. The host lifecycle manager is single-process/single-host.

## Alternatives

- Global lock held across provider calls: blocks control-plane operations and serializes work.
- Swap a mutable provider reference during invocation: can split one operation across versions.
- Immediately close a loader on route update: unsafe for in-flight work and old transactions.
- Grace-period-only unload: cannot prove no active references or unresolved ownership.

## Rationale

Leases define the lifetime of a selected implementation; immutable snapshots make ordinary reads cheap. Explicit drain states and bounded waits make failure to quiesce visible rather than claiming successful unload.

## Consequences

- Lifecycle state and acquisition must be race-safe and covered by coordinated concurrency tests.
- A drain may remain pending or time out; operators must act on references and stuck calls.
- Thread interruption is cooperative and does not forcibly stop hostile code.
- M5 does not sandbox in-process plugins or guarantee class-loader/metaspace reclamation.

## Risks

- Leaked leases can block unload.
- A timed-out non-cooperative provider can retain threads/resources.

## Revisit Conditions

Revisit if a production orchestrator requires cross-process rollout or if synchronous in-process calls cannot meet isolation/availability goals.
