# Repayment Schedule

For principal `P`, nominal annual percentage `A`, and `n` monthly installments, the monthly decimal rate is `r = (A / 100) / 12`.

```text
payment = P * r * (1+r)^n / ((1+r)^n - 1)
zero-rate payment = P / n
```

The implementation uses `BigDecimal`, `MathContext.DECIMAL128` intermediate calculations, currency-scale 2, and `HALF_UP` rounding. Each period's interest is computed from opening principal. The final installment absorbs the rounding residual so principal sum equals the original principal and the remaining balance ends at zero. Nonpositive principal/term, negative rate, excess currency precision, or non-amortizing payments are rejected.

Due dates are assigned only after confirmed disbursement. The first is one calendar month after actual disbursement; later dates preserve the original day anchor with month-end clamping (Jan 31 → Feb 28/29 → Mar 31). These are synthetic demo conventions, not an offer or universal financial standard. See [ADR-010](../adr/ADR-010-repayment-plan-generation.md).
