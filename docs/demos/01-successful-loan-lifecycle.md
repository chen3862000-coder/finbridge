# Demo 1: Successful Loan Lifecycle

## Goal

Demonstrate the synthetic happy path from authenticated application through risk approval, origination, confirmed synthetic disbursement, and active repayment plan.

## Run

Start the local demo using `docs/guides/getting-started.md`, then from `frontend/` run:

```sh
FINBRIDGE_DEMO_ALLOW_INSECURE_TLS=true npm run test:e2e
```

## Expected Behavior

The live script submits exact decimal text `1234.56`, receives a synthetic `APPROVE`, reads `APPROVED`, originates a `PENDING_DISBURSEMENT` loan, verifies an inactive plan, dispatches one synthetic AlphaPay payment, and then verifies `SUCCEEDED`, `ACTIVE` loan/plan, payment status, and reconciliation. The script does not use a real payment provider.

## Evidence

- Executable check: `scripts/demo-http-e2e.mjs` `borrowerJourney`
- Java tests: `JdbcLendingCoreIntegrationTest.durableDisbursementPinsOwnerAndActivatesLoanPlanOnlyAfterPersistedSuccess`
- Service tests: `RiskAssessmentServiceTest`, `LoanOriginationServiceTest`, `DisbursementServiceTest`
- Related design: ADR-007, ADR-009, ADR-010, ADR-012, ADR-014
- Actual run recorded: M7 Demo Review Record in `docs/quality/architecture-review-checklist.md`

## Limits

This synthetic happy-path demo does not represent underwriting or settlement performance. Ambiguous outcomes are covered by tests, not triggered in this happy-path script.
