# Transaction Consistency

## Local Transaction Boundaries

PostgreSQL transactions protect local invariants: application idempotency, workflow and risk claim ownership, atomic loan/repayment-plan/installment origination, optimistic plugin-binding revisions, payment intent, and confirmed-success activation. Unique constraints and compare-and-set versions arbitrate duplicate or stale operations.

External provider calls are not made while holding those database transactions. A durable risk or payment step is claimed first, the provider is invoked outside the transaction, and the result is written afterward. Risk reads may be repeated after interruption. Payment side effects are not blindly repeated: an ambiguous result remains unresolved and is queried from the original owner.

## Failure Semantics

- Failure before a durable claim creates no external effect.
- Failure after risk claim can repeat read-only provider queries and complete the reserved assessment.
- Failure after payment dispatch claim but before a definitive result is represented as unknown; query-only reconciliation owns recovery.
- Confirmed payment success and loan/plan activation are a single local transaction. Recovery can observe committed success without creating a second loan or plan.
- Startup recovery is single-host and idempotent for the implemented workflow; no distributed worker/fencing guarantee is claimed.

## Evidence

- `finbridge-lending-core/src/main/java/io/github/finbridge/lending/persistence/JdbcLendingAggregateRepository.java`
- `finbridge-lending-core/src/main/java/io/github/finbridge/lending/persistence/JdbcDisbursementRepository.java`
- `finbridge-host/src/main/java/io/github/finbridge/host/PluginControlPlaneRepository.java`
- `finbridge-host/src/main/java/io/github/finbridge/host/PluginRecoveryRunner.java`
- `finbridge-lending-core/src/test/java/io/github/finbridge/lending/persistence/JdbcLendingCoreIntegrationTest.java`
- `finbridge-host/src/test/java/io/github/finbridge/host/PluginControlPlaneIntegrationTest.java`
- `finbridge-host/src/test/java/io/github/finbridge/host/PluginRecoveryRunnerTest.java`
- ADR-008, ADR-011, ADR-013

The recorded root JDK 21 `clean verify` runs these Testcontainers/PostgreSQL recovery, concurrency, and transaction-boundary scenarios. It does not establish multi-node consistency.
