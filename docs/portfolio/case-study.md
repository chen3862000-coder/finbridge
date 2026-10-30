# FinBridge: Evidence-Driven Pluggable Lending Platform

## Summary

FinBridge is a synthetic Java 21 modular monolith demonstrating how a lending core can orchestrate risk and payment capabilities supplied by separately packaged provider JARs. The repository emphasizes durable workflow state, tenant-scoped routing, immutable payment ownership, and explicit handling of uncertain external outcomes.

## Engineering Problem

Provider integrations often entangle core decision logic, runtime dependencies, customer identity, and external side effects. FinBridge separates typed SPI contracts and provider artifacts from the core workflow, persists decisions and payment ownership, and limits retries when an external payment result is ambiguous.

## Design and Implemented Scope

- Java 21 Maven modules separate SPI, plugin runtime, lending core, risk/payment gateways, host composition, and a local demo application.
- Synthetic risk and payment providers are external JARs. Integration tests load assembled JARs and verify parent-shared SPI identity and provider-private class-loader separation.
- Plugin lifecycle callbacks run on separate bounded init/health and close worker pools. Callback deadlines fail fast on executor saturation and quarantine timed-out providers. Java interruption cannot forcibly stop arbitrary in-process plugin code.
- PostgreSQL persists applications, assessments, workflow steps, loans, repayment plans, payments, tenant bindings, and lifecycle audit.
- Monetary values use `BigDecimal`. Loan/plan activation follows persisted confirmed disbursement success.
- The local security demonstration uses Keycloak OIDC/PKCE, server-side sessions, CSRF, issuer/subject-to-membership mapping, and mutually exclusive borrower/admin route roles. It includes CSP, Permissions Policy, and a local demo threat model; it is not production-ready or a production security recommendation.

## Evidence

- Current working-tree JDK 21 `./mvnw --batch-mode --no-transfer-progress clean verify`: 17 modules passed; 301 tests, 0 failures/errors/skips; Enforcer, PMD, SpotBugs, and applicable JaCoCo gates passed. The separate runtime module `clean verify` passed 60 tests, including non-cooperative real-JAR init/health/close callbacks.
- Frontend `npm ci`, `npm run build`, `npm test` (11 tests), and `npm run test:a11y` (3 viewport/keyboard/WCAG checks) passed. Both `npm audit` and `npm audit --omit=dev` report zero vulnerabilities.
- Clean synthetic Compose stack started with healthy PostgreSQL, Keycloak, and API services. Seed counts: 2 tenants, 6 memberships, 1 product, 3 active plugins, and 8 bindings. The borrower HTTPS route returned HTTP 200.
- Current-source authenticated HTTP E2E passed application/risk (APPROVE, REVIEW, blacklist rejection, critical-fraud rejection), origination, repayment plan, synthetic disbursement/status/reconciliation, BetaPay routing for beta, FraudSignalZ install/binding, AlphaPay v1/v2 rollback, cookie/CSRF logout, hostile tenant/applicant field rejection, same-tenant borrower and cross-tenant isolation, and borrower/admin role separation.
- Captured signed-out and authenticated synthetic screenshots for borrower applications, loan book, workflow desktop/mobile, payment owner, plugin registry/audit, and tenant routing desktop/mobile. See [`screenshots/README.md`](screenshots/README.md) for what each shows and capture limitations.

Exact command output and current source identity are recorded in `docs/quality/architecture-review-checklist.md`.

See `docs/quality/architecture-review-checklist.md` for requirement traceability, exact verification records, and remaining gaps. See `docs/adr/ADR-014-authenticated-borrower-demo-api.md` for the local identity boundary.

## Explicit Limitations

This is not a production lending or identity system. Risk decisions and provider effects are synthetic. Keycloak, seeded passwords, the self-signed certificate, and the E2E TLS verification bypass are local-only. Plugin class-loader isolation is not sandboxing, and a timed-out in-process callback may continue if it ignores interruption. The host runtime, binding publication, recovery, and sessions are single-host. The current working tree passed Java, frontend, Compose, and authenticated API checks. These are local demonstration results, not a production security review. See `docs/security/threat-model.md` for the scoped local threat model and residual risks. No performance, financial, underwriting, settlement, or compliance metrics are claimed.
