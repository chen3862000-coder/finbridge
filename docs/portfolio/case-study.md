# FinBridge: Evidence-Driven Pluggable Lending Platform

## Summary

FinBridge is a synthetic Java 21 modular monolith demonstrating how a lending core can orchestrate risk and payment capabilities supplied by separately packaged provider JARs. The repository emphasizes durable workflow state, tenant-scoped routing, immutable payment ownership, and explicit handling of uncertain external outcomes.

## Engineering Problem

Provider integrations often entangle core decision logic, runtime dependencies, customer identity, and external side effects. FinBridge separates typed SPI contracts and provider artifacts from the core workflow, persists decisions and payment ownership, and limits retries when an external payment result is ambiguous.

## Design and Implemented Scope

- Java 21 Maven modules separate SPI, plugin runtime, lending core, risk/payment gateways, host composition, and a local demo application.
- Synthetic risk and payment providers are external JARs. Integration tests load assembled JARs and verify parent-shared SPI identity and provider-private class-loader separation.
- PostgreSQL persists applications, assessments, workflow steps, loans, repayment plans, payments, tenant bindings, and lifecycle audit.
- Monetary values use `BigDecimal`. Loan/plan activation follows persisted confirmed disbursement success.
- The local demo uses Keycloak OIDC/PKCE, server-side sessions, CSRF, issuer/subject-to-membership mapping, and mutually exclusive borrower/admin route roles.

## Evidence

- Root JDK 21 `./mvnw --batch-mode --no-transfer-progress clean verify`: 17 modules passed; 257 tests, 0 failures/errors/skips; Enforcer, PMD, SpotBugs, and JaCoCo passed.
- Frontend `npm run build` passed; `npm test` passed 4 tests; `npm audit --omit=dev` reported zero production dependency vulnerabilities. `npm ci` reported 3 full-tree vulnerabilities, including development dependencies.
- Fresh local Compose stack started with healthy PostgreSQL, Keycloak, and API services. The Console returned HTTP 200 on the local HTTPS route.
- Authenticated local HTTP E2E passed application/risk, origination, repayment plan, synthetic disbursement/status/reconciliation, cross-tenant denial, and borrower/admin route separation.

See `docs/quality/architecture-review-checklist.md` for requirement traceability, exact verification records, and remaining gaps. See `docs/adr/ADR-014-authenticated-borrower-demo-api.md` for the local identity boundary.

## Explicit Limitations

This is not a production lending or identity system. Risk decisions and provider effects are synthetic. Keycloak, seeded passwords, the self-signed certificate, and the E2E TLS verification bypass are local-only. Plugin class-loader isolation is not sandboxing. The host runtime, binding publication, recovery, and sessions are single-host. The local demo has not completed negative OIDC token callback testing, production threat modeling, clean-clone verification, screenshots, or the full seven-demo release evidence set. No performance, financial, underwriting, settlement, or compliance metrics are claimed.
