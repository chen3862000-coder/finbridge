# Repayment Plan Calculation: M2

**Policy:** User-approved M2 decision, recorded in ADR-010. Demo convention only; not a real loan offer or regulated financial calculation.

## Calculation

For principal `P`, annual nominal percentage `A`, `n` installments, and monthly decimal rate `r = (A / 100) / 12`:

```text
payment = P * r * (1+r)^n / ((1+r)^n - 1)
zero-rate payment = P / n
```

Implementation is `RepaymentScheduleCalculator`. It uses `BigDecimal`, `MathContext.DECIMAL128`, scale-2 `HALF_UP` monetary rounding, computes each period's interest on opening principal, and uses payment minus interest for principal. It adjusts the last principal to exactly the remaining balance. The implementation rejects nonpositive principal/term, negative interest, excess principal currency precision, and a scheduled payment that does not amortize the principal.

## Invariants and Examples

- Installment principal sum equals original principal exactly.
- Last remaining principal is zero and no remaining principal is negative.
- Each total equals its principal plus interest at scale 2.
- Zero-rate CNY 1,000 over three periods yields 333.33, 333.33, 333.34.
- CNY 10,000 at 12% annual nominal rate for 12 months yields first scheduled payment 888.49 and total interest 661.86 under the selected convention.
- Values are deterministic for identical inputs.

`RepaymentScheduleCalculatorTest` covers zero rate, amortizing example, rounded residual, reproducibility, invalid input, and non-amortizing cases. `DomainStateTest` verifies the schedule remains pending until activation and dates are then attached.

## Due Date Policy

Principal/interest amounts can be generated before disbursement, but due dates are assigned only at confirmed activation. Installment 1 is one calendar month after the actual disbursement date; later installments are monthly anniversaries. Month ends clamp to the final day of each month while preserving the original anchor day. Example: Jan 31 -> Feb 28/29 -> Mar 31. `MonthlyDueDatePolicy` computes each date directly from the original date, so February clamping does not drift future months.

No installment accrues a due/overdue state while the loan remains pending disbursement. Loan and plan activation together with final dates is a future M4 workflow operation after confirmed payment. M2 provides the domain guard and schedule function, not disbursement orchestration.
