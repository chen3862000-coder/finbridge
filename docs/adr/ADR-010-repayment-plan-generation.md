# ADR-010: Repayment Plan Calculation and Activation

- **Status:** Accepted by user for M2 on 2026-10-26
- **Date:** 2026-10-26

## Context

The portfolio workflow needs deterministic equal-payment schedules. Floating-point arithmetic, implicit rounding, or due dates derived from a pending approval date can make totals inconsistent or schedules stale before funds are disbursed.

## Decision

Use `BigDecimal` for all monetary and rate calculations, `MathContext.DECIMAL128` intermediate precision, currency scale 2, and `HALF_UP` monetary rounding. For each installment calculate rounded interest and principal; adjust the final installment to clear remaining principal exactly. Validate principal reconciliation, nonnegative balances, deterministic results, and zero-interest behavior. Generate installment amounts before disbursement but finalize due dates on confirmed activation, using monthly anniversaries anchored to the actual disbursement date with month-end clamping to the original anchor day (so Jan 31 -> Feb 28/29 -> Mar 31).

## Alternatives

- `double` arithmetic: compact but introduces binary rounding errors.
- Round only the final total: installments may not reconcile to principal or payment amounts.
- Never adjust final installment: residual principal/interest can remain.
- Date schedule from application/approval: prolonged payment processing can make first dates stale or already overdue.

## Rationale

Explicit decimal operations and a final residual rule produce a reproducible schedule whose principal reconciles. Activation-date schedule anchoring matches the actual synthetic disbursement date and keeps a pending schedule inactive.

## Consequences

- Product/calculation policy version and inputs must be persisted for reproducibility.
- Due-date month-end behavior is part of the contract and needs tests (e.g. Jan 31 -> Feb end -> Mar 31).
- Exact product/rate policy remains synthetic and versioned; the rounding and due-date conventions above are approved demo rules.

## Risks

- `HALF_UP` is a demo convention, not a universal financial standard.
- Different jurisdictions/products may require day-count conventions, fees, or different accrual precision outside MVP scope.

## Revisit Conditions

Revisit for a real product specification, another currency/scale, different day-count basis, fee model, or legal/financial policy requirement.
