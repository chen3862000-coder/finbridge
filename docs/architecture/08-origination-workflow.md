# Origination Workflow

The origination workflow is a PostgreSQL-backed state machine, not one long transaction or a distributed workflow service. Each step reserves durable identity before external calls and commits local state in short transactions.

```mermaid
flowchart TD
  A[Submit idempotent application]
  B[Persist application and workflow claim]
  C[Read typed risk data outside transaction]
  D[Persist assessment and decision]
  E{Approved}
  F[Return review or rejection]
  G[Create pending loan and plan atomically]
  H[Claim payment and pin provider owner]
  I[Dispatch outside transaction]
  J[Persist provider result]
  K{Confirmed success}
  L[Activate loan and repayment plan]
  M[Keep loan and plan inactive]

  A --> B --> C --> D --> E
  E -->|No| F
  E -->|Yes| G --> H --> I --> J --> K
  K -->|Yes| L
  K -->|No or unknown| M
```

Completed risk assessments replay from persistence. Interrupted risk reads may be repeated because included risk providers are read-only fixtures. Loan origination is unique per application and atomic. Payment ambiguity follows query-only recovery against the original provider. Startup recovery and in-memory routing are single-host; there is no distributed fencing. See [Transaction Consistency](13-transaction-consistency.md).
