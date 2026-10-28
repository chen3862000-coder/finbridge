# Loan Origination Workflow

## Scope

This document describes the implemented single-host application, risk, origination, payment, and recovery workflow. It does not claim distributed workflow execution or multi-host fencing.

## Workflow

`OriginationWorkflow` persists one workflow per application. The `RiskAssessmentService` reserves the risk workflow and assessment claim before calling read-only risk providers. Successful evaluation persists normalized provider evidence, a core-owned synthetic decision, the application transition, and completed workflow state. A completed workflow replay returns the persisted assessment without repeating provider reads.

An approved application can be originated by `LoanOriginationService`. PostgreSQL persists the loan, inactive repayment plan, installments, application transition, and workflow state atomically. Duplicate origination is constrained by the application identity and returns the existing aggregate rather than creating a second loan.

Disbursement is a later durable step. A payment transaction and immutable provider owner are claimed before the provider call. The call executes outside the database transaction. A successful outcome is persisted before the loan and repayment plan are activated. An ambiguous dispatch is recorded as unknown and recovery queries the original provider only; it does not blindly submit again or fail over.

## Recovery Boundaries

- Risk-provider reads are safe to repeat after an interrupted attempt because the providers are read-only synthetic fixtures.
- A claimed payment dispatch whose result is unknown is recovered by status query against the persisted provider/version and transaction or idempotency reference.
- Confirmed success and aggregate activation are committed transactionally. A replay observes persisted success.
- Recovery is single-host startup work. The design does not provide distributed leases, multi-host fencing, or an external workflow engine.

## Evidence

- `finbridge-lending-core/src/main/java/io/github/finbridge/lending/application/RiskAssessmentService.java`
- `finbridge-lending-core/src/main/java/io/github/finbridge/lending/application/LoanOriginationService.java`
- `finbridge-lending-core/src/main/java/io/github/finbridge/lending/persistence/JdbcLendingAggregateRepository.java`
- `finbridge-lending-core/src/test/java/io/github/finbridge/lending/persistence/JdbcLendingCoreIntegrationTest.java`
- `finbridge-lending-core/src/test/java/io/github/finbridge/lending/application/RiskAssessmentServiceTest.java`
- `finbridge-lending-core/src/test/java/io/github/finbridge/lending/application/LoanOriginationServiceTest.java`
- `finbridge-lending-core/src/test/java/io/github/finbridge/lending/application/DisbursementServiceTest.java`
- ADR-008, ADR-009, ADR-013

The recorded JDK 21 root `clean verify` runs PostgreSQL/Testcontainers workflow, duplicate, recovery, and activation tests. The test records are the evidence; this overview does not add a production guarantee beyond those scenarios.
