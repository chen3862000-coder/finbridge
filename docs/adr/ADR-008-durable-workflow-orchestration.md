# ADR-008: Durable Loan Origination Workflow

- **Status:** Proposed, awaiting v2 M0 approval
- **Date:** 2026-10-26

## Context

Loan origination spans local database changes and external risk/payment calls. A single database transaction cannot cover those side effects, and in-memory orchestration loses progress on restart. The MVP needs recoverable steps without taking on a distributed workflow platform.

## Decision

Use a PostgreSQL-backed workflow/process-manager state machine with short local transactions, optimistic step transitions, idempotency keys, durable attempts, and explicit resume/reconciliation. Make workflow state authoritative across restart. Keep provider calls outside DB transactions. Do not add Temporal, Camunda, Kafka, or an event bus in the MVP.

## Alternatives

- One long DB transaction: cannot atomically include provider side effects and holds locks across I/O.
- In-memory sequence/service method: simple happy path but cannot reliably recover or deduplicate after crash.
- Temporal/Camunda/distributed workflow engine: stronger orchestration features but adds deployment and operational scope without a demonstrated MVP need.
- Outbox plus broker: useful for asynchronous cross-process delivery, but no broker consumer exists in the initial single-host workflow.

## Rationale

The workflow is finite and has explicit domain states. PostgreSQL can persist step progress and uniqueness constraints while keeping the stack small. Saga-style forward recovery is sufficient for synthetic workflows; irreversible payment ambiguity is represented explicitly rather than compensated blindly.

## Consequences

- Every external step needs a durable pre-call intent/attempt and post-call result boundary.
- Recovery must distinguish proven-not-called from possibly-called operations; the latter become UNKNOWN and reconcile through the original provider.
- Startup/admin/API recovery behavior and a workflow version are explicit implementation concerns.
- If reliable cross-process events are later required, introduce an outbox deliberately.

## Risks

- Homegrown workflow transition logic can be incorrect or difficult to observe.
- A database-backed workflow table does not itself guarantee exactly-once side effects.
- Conservative UNKNOWN states may require operator reconciliation.

## Revisit Conditions

Revisit if workflow branching, timers, compensation, throughput, or cross-service coordination outgrows the small state machine, or if events must be delivered to independent consumers.
