# Architecture Decision Records

The v2 ADRs are **proposed**, not accepted. They capture the choices recommended for review before implementation. Update status to `Accepted` only after explicit M0 approval; revise an accepted decision with a new ADR rather than rewriting history. The ADR set has been revised for v2; prior v1 ADR files are not separately archived.

| ADR | Decision |
|---|---|
| [ADR-001](ADR-001-java-spi-vs-plugin-frameworks.md) | Java SPI and a small explicit runtime |
| [ADR-002](ADR-002-modular-monolith.md) | Modular monolith with independently packaged providers |
| [ADR-003](ADR-003-classloader-strategy.md) | Controlled per-artifact class loading |
| [ADR-004](ADR-004-typed-spi-contracts.md) | Typed, explicitly versioned SPI contracts |
| [ADR-005](ADR-005-tenant-capability-routing.md) | Accepted M5 authenticated tenant facade and durable capability routing |
| [ADR-006](ADR-006-hot-swap-consistency.md) | Accepted M5 durable artifact lifecycle, owner retention, upgrade and rollback |
| [ADR-007](ADR-007-payment-transaction-ownership.md) | Accepted M4 immutable payment ownership and query-only ambiguity recovery |
| [ADR-008](ADR-008-durable-workflow-orchestration.md) | Accepted durable origination state machine and single-host startup recovery |
| [ADR-009](ADR-009-loan-activation-after-disbursement.md) | Accepted M4 activation only after persisted confirmed disbursement |
| [ADR-010](ADR-010-repayment-plan-generation.md) | Deterministic amortization and schedule dates |
| [ADR-011](ADR-011-postgresql-persistence.md) | PostgreSQL for durable platform state |
| [ADR-012](ADR-012-synthetic-risk-policy.md) | User-approved deterministic synthetic risk policy |
| [ADR-013](ADR-013-durable-risk-step-recovery.md) | Durable M3 risk-step recovery; payment workflow remains M4 |

Related active plan and evidence status: [M0 architecture proposal v2](../architecture/m0-proposal-v2.md). The original [v1 proposal](../architecture/m0-proposal.md) is superseded history.
