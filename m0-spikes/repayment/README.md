# M0 Repayment Calculation Spike

This standalone Java 21 spike checks the proposed equal-payment calculation and date anchoring without adding a Lending Core module. It uses `BigDecimal`, `MathContext.DECIMAL128`, two-place `HALF_UP` rounding, final-installment adjustment, and original-day month-end clamping.

Run:

```bash
JAVA_HOME="/path/to/jdk-21" PATH="/path/to/jdk-21/bin:$PATH" bash m0-spikes/repayment/run.sh
```

This validates arithmetic invariants for the proposed demo convention only; it does not establish a regulated or generally correct financial product methodology.
