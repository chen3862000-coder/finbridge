# ADR-007: Payment Transaction Ownership

- **Status:** Proposed, awaiting M0 approval
- **Date:** 2026-10-26

## Context

Payment outcome may be ambiguous after timeout or host failure. Tenant bindings may change while a transaction is outstanding. Retrying through the newly selected provider risks duplicate or misdirected effects.

## Decision

Before provider invocation, persist a unique idempotency claim, canonical request fingerprint, and selected plugin ID/version. Persist subsequent result against that owner. Duplicate matching requests do not invoke again; conflicting payloads fail. Ambiguous outcomes become `UNKNOWN` and can be reconciled only through the original provider version. Unresolved transactions prevent artifact unload.

## Alternatives

- Resolve current tenant binding for every status/retry: may query a provider that never created the transaction.
- Automatically fail over on timeout: can duplicate a completed side effect.
- Hold a DB transaction open during provider I/O: increases lock duration and still does not make external side effects atomic.
- Claim end-to-end exactly-once: not achievable from host-side database semantics alone without provider cooperation and protocol guarantees.

## Rationale

Persisted ownership survives route upgrades and restart. Unique constraints and pre-call claims prevent concurrent duplicate invocations by this host, while UNKNOWN accurately expresses uncertainty instead of fabricating failure.

## Consequences

- Provider artifacts must be retained while transactions are unresolved.
- Missing original artifacts block reconciliation and need an operational alert.
- The guarantee is bounded to host idempotency behavior; it is not settlement exactly-once.

## Risks

- Provider-side idempotency behavior may differ or be absent.
- A crash around the side effect can leave UNKNOWN indefinitely without provider status support.

## Revisit Conditions

Revisit when real provider protocols are selected, including their idempotency, query, retention, and reconciliation guarantees; do not weaken ownership before that analysis.
