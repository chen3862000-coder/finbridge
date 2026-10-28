# Payment Gateway and Disbursement

## Ownership and Dispatch

`DisbursementService` claims a payment transaction with a stable idempotency key, payload fingerprint, attempt identifier, and immutable plugin/version owner before dispatch. `PaymentGateway` routes directly to that owner rather than consulting the current tenant binding for an in-flight payment. Provider invocation occurs outside the database transaction.

The transaction records the provider's typed status and provider transaction reference. A confirmed `SUCCEEDED` outcome is persisted before `JdbcDisbursementRepository` activates the loan and repayment plan. `Loan` rejects an active state without confirmed transaction and timestamps.

## Ambiguity and Reconciliation

An exception, timeout, or process interruption after the durable claim cannot prove whether the external effect occurred. The status is retained as `UNKNOWN`/reconciliation-required. Recovery queries the original provider using the stored provider transaction ID or the paired host transaction ID and client idempotency key. It never blindly resubmits and never changes provider owner or fails over. Definitive failure is terminal in this MVP; no retry-intent feature is claimed.

The demo E2E exercises one successful synthetic AlphaPay dispatch and a subsequent status/reconciliation read. It does not simulate an ambiguous external effect; ambiguity and recovery evidence comes from deterministic unit and PostgreSQL integration tests.

## Evidence

- `finbridge-payment-gateway/src/main/java/io/github/finbridge/payment/PaymentGateway.java`
- `finbridge-lending-core/src/main/java/io/github/finbridge/lending/application/DisbursementService.java`
- `finbridge-lending-core/src/main/java/io/github/finbridge/lending/persistence/JdbcDisbursementRepository.java`
- `finbridge-lending-core/src/main/java/io/github/finbridge/lending/domain/PaymentTransaction.java`
- `finbridge-payment-gateway/src/test/java/io/github/finbridge/payment/PaymentGatewayIntegrationTest.java`
- `finbridge-lending-core/src/test/java/io/github/finbridge/lending/application/DisbursementServiceTest.java`
- `finbridge-lending-core/src/test/java/io/github/finbridge/lending/persistence/JdbcLendingCoreIntegrationTest.java`
- ADR-007, ADR-008, ADR-009

The recorded JDK 21 root `clean verify` passes real synthetic-provider JAR and PostgreSQL recovery tests. This is not a real payment integration or settlement guarantee.
