# ADR-009: Loan Activation After Confirmed Disbursement

- **Status:** Accepted design constraint; activation operation is deferred to M4
- **Date:** 2026-10-26

## Context

Risk approval is not cash delivery. A loan and repayment schedule can be created before disbursement, while payment can fail, process asynchronously, or have an ambiguous result. Treating approval or payment submission as activation would misstate the business lifecycle.

## Decision

Create the Loan in `PENDING_DISBURSEMENT` and RepaymentPlan in `PENDING_ACTIVATION`. Persist the payment intent and provider owner, then invoke outside the DB transaction. Only after a confirmed successful payment result has been persisted may one local transaction activate both the Loan and plan, set activation/disbursement timestamps and finalize due dates. Processing/UNKNOWN stays non-active; definitive failure stays non-active and is recorded distinctly.

## Alternatives

- Activate at approval: conflates credit decision and disbursement.
- Activate on submission/accepted response: an accepted asynchronous payment is not proof of success.
- Keep loan/plan creation until payment success: loses a durable representation of approved origination and complicates recovery/inspection.
- Activate loan and plan in separate transactions: can leave inconsistent active state.

## Rationale

Persisted confirmed success is the explicit boundary between approved credit and an active loan. Atomic local activation prevents the loan and repayment plan from disagreeing, while the external payment remains an independently committed fact.

## Consequences

- Loan and schedule exist in a non-active state during processing/reconciliation.
- Due dates should be finalized at activation so pending time does not make the schedule stale.
- Recovery after payment success but before activation can safely complete activation without another provider call.
- The demo still cannot prove provider settlement beyond its synthetic contract.

## Risks

- Provider `SUCCEEDED` semantics are synthetic and may differ from a real provider's settlement finality.
- A payment may succeed while the local database is unavailable; recovery must query/reconcile by stored owner.

## Revisit Conditions

Revisit when a real disbursement protocol defines intermediate/final settlement statuses, reversals, or legally significant activation rules.
