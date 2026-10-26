# Lending Domain Model: M2

M2 introduces the domain and persistence foundation for the synthetic cash-loan product. It deliberately does not yet execute provider risk calls or disburse payments; those are M3 and M4 responsibilities. The core concepts are separate so approval, origination, repayment, and payment can have independent state and ownership.

## Model Boundaries

| Model | M2 implementation | Key invariant / evidence |
|---|---|---|
| `LoanApplication` | `lending.domain.LoanApplication` | Tenant-owned request; explicit status transitions and optimistic version; tested in `DomainStateTest` and JDBC version-conflict test. |
| `LoanProduct` | `lending.domain.LoanProduct`, `JdbcLoanProductRepository` | Versioned CNY terms and bounds; application stores product version; validates principal/currency/term; tested in domain and PostgreSQL tests. |
| `RiskAssessment` | `lending.domain.RiskAssessment`, `JdbcRiskAssessmentRepository` | Stores normalized facts, core decision, reason codes, policy version and provider execution references; test persists/loads it against PostgreSQL. Provider orchestration and decision policy remain M3. |
| `Loan` | `lending.domain.Loan` | Unique application ownership; starts pending disbursement and can activate only after a processing payment intent is associated with confirmed timestamp. It does not become ACTIVE merely on approval. |
| `RepaymentPlan` | `lending.domain.RepaymentPlan` | Unique loan association; is pending activation before disbursement; final dates are added when activated. |
| `RepaymentInstallment` | `lending.domain.RepaymentInstallment` | Ordered installments with principal, interest, total, remaining balance, and state. Money is `BigDecimal`; due date is absent until activation. |
| `PaymentTransaction` | Not implemented in M2 | Its durable idempotency/provider ownership and reconciliation are M4; no payment effect is claimed by Lending Core M2. |

## Transactional Boundary

`LoanOriginationService` verifies a tenant-scoped approved application, resolves the exact product version captured on that application, and creates a pending Loan and pending RepaymentPlan. `JdbcLendingAggregateRepository.originate` inserts Loan, plan, and all installments within one `TransactionTemplate` local transaction. PostgreSQL uniqueness on application ID and loan ID protects against duplicate origination. If a duplicate race observes an existing fully persisted aggregate, the repository returns it; it does not return a partial aggregate.

The database is the source of durable application/risk/loan/schedule state. No external provider call occurs in this module. The separate durable origination workflow/payment intent boundary is M4. Risk evidence is represented and persisted in M2, while risk provider retrieval and policy execution are M3.

## State and Tenant Invariants

- Application state is not loan state. Risk decision updates the application to APPROVED, REJECTED, or MANUAL_REVIEW; only approval may enter origination.
- Rejected/manual-review applications cannot create Loans or plans.
- A unique `loans.application_id` allows at most one Loan per application.
- Loan and plan are inserted atomically and initially non-active.
- Payment confirmation and the actual activation operation are not implemented in M2. The domain exposes the accepted ADR-009 guard; M4 implements the coordinator that may activate only after durable payment success.
- Repositories require tenant ID on application/loan reads; request DTO tenant ID remains synthetic M2 input, with authenticated principal enforcement deferred to host security work.

## Schema

Flyway migration `finbridge-lending-core/src/main/resources/db/migration/V1__lending_core.sql` creates tenant/product, application, normalized risk assessment, loan, repayment plan, and installment tables. It uses foreign keys, product snapshots/version references, optimistic versions, application/plan uniqueness, idempotency uniqueness, audit timestamps, and PostgreSQL `numeric` monetary fields. Provider execution references are JSONB containing IDs/version/capability only, not raw provider payloads.

Evidence is in `JdbcLendingCoreIntegrationTest`, which runs the migration in a real PostgreSQL 16 Testcontainer and checks idempotency, concurrent request claims, tenant scoping, optimistic update conflicts, atomic Loan/plan/installment creation, duplicate origination recovery, and risk assessment serialization.
