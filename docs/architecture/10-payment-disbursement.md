# Payment and Disbursement

Before a provider call, the host claims a durable payment transaction containing the idempotency key, request fingerprint, attempt identity, and immutable provider ID/version. The call runs outside a database transaction. A matching replay does not redispatch; conflicting payload reuse is rejected.

```mermaid
stateDiagram-v2
  [*] --> READY: durable intent claimed
  READY --> PROCESSING: provider accepts request
  PROCESSING --> SUCCEEDED: provider confirms
  PROCESSING --> FAILED: definitive failure
  PROCESSING --> UNKNOWN: timeout/interruption/uncertain result
  UNKNOWN --> PROCESSING: status still pending
  UNKNOWN --> SUCCEEDED: original provider confirms success
  UNKNOWN --> FAILED: original provider confirms failure
```

Only persisted confirmed success activates the loan and repayment plan and assigns due dates, in one local transaction. `UNKNOWN` is neither failure nor success: query the original owner using provider transaction ID or host transaction/idempotency references. Never blindly resubmit, fail over, or change historical owner. Definitive failure is terminal in this demo; retry-intent policy is not implemented. This does not guarantee exactly-once settlement. See [ADR-007](../adr/ADR-007-payment-transaction-ownership.md), [ADR-009](../adr/ADR-009-loan-activation-after-disbursement.md), and [Transaction Consistency](13-transaction-consistency.md).
