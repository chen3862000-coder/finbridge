# ADR-007: Payment Transaction Ownership

- **Status:** Accepted for M4 implementation
- **Date:** 2026-10-27

## Context

Payment outcome may be ambiguous after timeout or host failure. Tenant bindings may change while a transaction is outstanding. Retrying through the newly selected provider risks duplicate or misdirected effects.

## Decision

Before provider invocation, persist a unique idempotency claim, canonical request fingerprint, and selected plugin ID/version. Persist subsequent result against that owner. Duplicate matching requests do not invoke again; conflicting payloads fail. Ambiguous outcomes become `UNKNOWN` and can be reconciled only through the original provider version. Lending Core exposes a predicate for unresolved transactions by owner, and the runtime drain/unload API accepts a retention policy that can use it. Host lifecycle composition that supplies this policy remains M5 work.

Reconciliation accepts exactly one reference: the provider transaction ID when known, or the host payment transaction ID together with the original client idempotency key when the response was lost. Status queries return a typed `PaymentResult` so a success discovered by either reference includes its provider transaction ID. An in-flight durable dispatch found on a repeated command or after restart is changed to `UNKNOWN` and is never resubmitted. Definitive `FAILED` is terminal in M4; a new payment intent/retry policy is not implemented.

## Alternatives

- Resolve current tenant binding for every status/retry: may query a provider that never created the transaction.
- Automatically fail over on timeout: can duplicate a completed side effect.
- Hold a DB transaction open during provider I/O: increases lock duration and still does not make external side effects atomic.
- Claim end-to-end exactly-once: not achievable from host-side database semantics alone without provider cooperation and protocol guarantees.

## Rationale

Persisted ownership survives route upgrades and restart. Unique constraints and pre-call claims prevent concurrent duplicate invocations by this host, while UNKNOWN accurately expresses uncertainty instead of fabricating failure.

## Consequences

- Deployment operations must retain provider artifacts while transactions are unresolved; M5 will wire the host lifecycle policy to the Lending Core predicate.
- Missing original artifacts block reconciliation and need an operational alert.
- The guarantee is bounded to host idempotency behavior; it is not settlement exactly-once.

## Risks

- Provider-side idempotency behavior may differ or be absent.
- A crash around the side effect can leave UNKNOWN indefinitely without provider status support.
- The host cannot prove the network call was not started once the dispatch claim committed; all such recovery is conservative and query-only.

## Revisit Conditions

Revisit when real provider protocols are selected, including their idempotency, query, retention, and reconciliation guarantees; do not weaken ownership before that analysis.
