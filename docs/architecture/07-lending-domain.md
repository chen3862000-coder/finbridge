# Lending Domain

FinBridge separates the application, assessment, loan, plan, and payment because they have different state transitions, ownership, and recovery rules.

| Concept | Responsibility and invariant |
|---|---|
| `LoanProduct` | Versioned currency, amount limits, terms, and rate. Application records the version used. |
| `LoanApplication` | Tenant/applicant request with exact amount, requested terms, idempotency identity, and constrained state transitions. It is not a loan. |
| `RiskAssessment` | Persisted normalized provider evidence, policy version, core decision, and reason codes. |
| `Loan` | Obligation created only for an approved application; unique per application; pending until confirmed disbursement. |
| `RepaymentPlan` / installments | One pending schedule per loan; principal reconciles exactly; dates and active status follow successful disbursement. |
| `PaymentTransaction` | Durable intent and outcome pinned to provider ID/version for status query and recovery. |

Application and product lookups are tenant-scoped. Duplicate application keys replay only when request fingerprints match; conflicting reuse is rejected. Origination persists the loan, pending plan, installments, application transition, and workflow updates atomically. See [DDD guide](02-domain-driven-design.md), [Workflow](08-origination-workflow.md), and [Repayment Schedule](11-repayment-schedule.md).
