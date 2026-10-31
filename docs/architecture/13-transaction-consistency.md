# Transaction Consistency

PostgreSQL transactions protect local invariants: application idempotency, workflow/risk claims, atomic loan-plan-installment origination, binding revisions, payment intent, and success-gated activation. Unique constraints and optimistic versions arbitrate duplicate or stale operations.

External provider calls never occur while holding those transactions. The host claims a durable step, commits, calls the provider, then persists the result. Risk reads may be repeated after interruption. Payment effects are not blindly repeated; ambiguous results remain unresolved and are queried from the immutable original provider owner.

| Failure point | Recovery behavior |
|---|---|
| Before durable claim | No provider effect has been requested. |
| After risk claim | Repeat read-only synthetic provider reads and complete the reserved assessment. |
| After payment dispatch claim, before definitive result | Keep outcome unknown and query original owner only. |
| After confirmed success is persisted | Replayed workflow observes success; loan/plan activation is committed locally and idempotently. |

These guarantees cover host persistence and recovery scenarios tested by PostgreSQL integration tests. They do not make an external provider part of the database transaction or guarantee exactly-once settlement. Startup recovery is single-host. See [Workflow](08-origination-workflow.md), [Payment](10-payment-disbursement.md), and ADR-008/011/013.
