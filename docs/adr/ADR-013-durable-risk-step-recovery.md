# ADR-013: Durable Recovery for the Risk Assessment Step

- **Status:** Accepted M3 implementation direction; payment orchestration remains M4
- **Date:** 2026-10-26

## Context

The updated v2 goal requires durable loan origination progress. The milestone plan originally placed all durable workflow code in M4, but M3 now explicitly includes a persisted risk step. Risk reads are synthetic/read-only, while the policy decision and assessment must be persisted before downstream origination.

## Decision

M3 persists a workflow row and unique risk-step attempt/assessment ownership around risk data retrieval. Claim and completion use short PostgreSQL transactions and optimistic versions. The external risk read occurs outside database transactions. On recovery, an incomplete risk read may be repeated because providers are contractually read-only synthetic fixtures; persist the completed normalized facts, provider/version references, decision, policy version, and application transition transactionally/idempotently. Do not create Loan, plan, payment intent, or invoke payment from M3. Payment ambiguity and irreversible side-effect recovery remain M4.

## Alternatives

- Defer all workflow persistence to M4: leaves M3 unable to provide restart-safe assessment progress and contradicts user-selected M3 scope.
- Hold a transaction across provider reads: lengthens locks without making external calls atomic.
- Add an external workflow engine: unnecessary for the bounded read/decision step.
- Treat the risk request as side-effecting: inconsistent with the synthetic read-only SPI contract; if a real provider is later selected, re-evaluate this assumption.

## Rationale

Risk acquisition is repeatable under the synthetic provider contract, and the local normalized assessment/decision is the durable milestone boundary. This extends the existing lightweight process-manager design only where required while reserving payment intent and irreversible operation handling for M4.

## Consequences

- Workflow/application transition and completed assessment need uniqueness/idempotency and optimistic concurrency tests.
- A retry can repeat provider reads but cannot duplicate downstream loan creation because M3 does not originate loans.
- M4 must extend the workflow state machine rather than replace it with an in-memory sequence.

## Risks

- A future provider may not be read-only or repeatable; adapter contract and retry semantics must then change before integration.
- Workflow state vocabulary must be extended carefully when payment intent and reconciliation are added.

## Revisit Conditions

Revisit if risk calls gain side effects, if a workflow engine becomes justified, or before M4 adds disbursement and crash-recovery states.
