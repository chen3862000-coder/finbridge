# Risk Assessment

Risk providers supply typed evidence such as credit score, blacklist, and fraud signal. They do not approve or reject applications. `RiskDataGateway` selects providers using tenant capability bindings, bounds provider execution, and normalizes unavailable or invalid results. Provider calls occur outside lending database transactions.

The lending core owns the deterministic synthetic policy:

| Condition | Synthetic result |
|---|---|
| Blacklist hit or critical fraud | Reject |
| Mandatory blacklist/credit evidence missing or invalid | Review |
| Score below 600 | Reject |
| Score 600–679 | Review |
| Score 680+ with required evidence and no disqualifier | Approve |
| Optional fraud evidence unavailable | Does not alone block approval; record reason |

These are demo thresholds, not validated underwriting. A reject or review creates no loan, repayment schedule, or payment. A completed assessment persists normalized facts, policy version, provider references, and reason codes. See [ADR-012](../adr/ADR-012-synthetic-risk-policy.md), [ADR-013](../adr/ADR-013-durable-risk-step-recovery.md), and the [demo risk scenarios](../guides/borrower-demo-usage.md).
