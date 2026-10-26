# ADR-011: PostgreSQL for Durable Platform State

- **Status:** Proposed, awaiting M0 approval
- **Date:** 2026-10-26

## Context

FinBridge must persist tenant bindings, idempotency claims, payment ownership, artifact metadata, traces, and lifecycle audit. Uniqueness, optimistic locking, and transaction boundaries are architectural evidence, not incidental storage details.

## Decision

Use PostgreSQL 16 with Flyway migrations and Spring JDBC for the MVP. Keep live class loaders and invocation leases in memory; persist desired/configuration and durable business/audit records. Support one host instance only.

## Alternatives

- In-memory store: cannot demonstrate restart recovery, uniqueness, or durable ownership.
- ORM-first persistence: viable, but JDBC makes SQL constraints and short explicit transaction boundaries clear for this bounded schema.
- Distributed database/configuration service: adds operational complexity without a multi-node MVP requirement.

## Rationale

PostgreSQL provides constraints and transactions needed to arbitrate idempotency and optimistic binding updates. Flyway makes schema evolution reproducible. JDBC keeps external-call transaction boundaries explicit.

## Consequences

- Database state is not proof of an active in-memory plugin after restart.
- Startup revalidates and reconstructs runtime state; it does not blindly trust persisted ACTIVE labels.
- Multi-host operation needs distributed lifecycle/routing coordination and is out of scope.

## Risks

- Single-host assumptions can be mistaken for horizontally scalable guarantees.
- Schema retention and audit growth need explicit policies.

## Revisit Conditions

Revisit persistence technology or coordination model if multiple host instances, higher write scale, or external configuration ownership becomes a requirement.
