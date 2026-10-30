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

- Published commit [`30d28e15034a580db8ec10661d739037378aa45f`](https://github.com/chen3862000-coder/finbridge/commit/30d28e15034a580db8ec10661d739037378aa45f) passed all three jobs in [GitHub Actions run 41049982380](https://github.com/chen3862000-coder/finbridge/actions/runs/41049982380): Java `clean verify` (17 modules, 301 tests), frontend build/unit/accessibility/production audit, and clean Compose authenticated E2E.
- Java Enforcer, PMD, SpotBugs, and applicable JaCoCo gates passed. Frontend ran 11 unit tests and 3 axe/viewport checks; both local full-tree and production-only npm audits report zero vulnerabilities.
- The clean CI Compose job verified service health and synthetic seed counts, requested the HTTPS borrower route, and passed application/risk (APPROVE, REVIEW, blacklist rejection, critical-fraud rejection), origination, repayment plan, synthetic disbursement/status/reconciliation, BetaPay routing, FraudSignalZ install/binding, AlphaPay v1/v2 rollback, session/CSRF/logout, hostile tenant/applicant rejection, tenant isolation, and borrower/admin role separation.
- Captured signed-out and authenticated synthetic screenshots for borrower applications, loan book, workflow desktop/mobile, payment owner, plugin registry/audit, and tenant routing desktop/mobile. See [`screenshots/README.md`](screenshots/README.md) for what each shows and capture limitations.

Exact job results, source SHA, and evidence links are recorded in `docs/quality/architecture-review-checklist.md`. The clean CI Compose run supersedes the earlier local Docker Hub timeout/no-build workaround as release evidence.

See `docs/quality/architecture-review-checklist.md` for requirement traceability, exact verification records, and remaining gaps. See `docs/adr/ADR-014-authenticated-borrower-demo-api.md` for the local identity boundary.

## Explicit Limitations

This is not a production lending or identity system. Risk decisions and provider effects are synthetic. Keycloak, seeded passwords, the self-signed certificate, and the E2E TLS verification bypass are local-only. Plugin class-loader isolation is not sandboxing, and a timed-out in-process callback may continue if it ignores interruption. The host runtime, binding publication, recovery, and sessions are single-host. Published commit `30d28e1` passed the Java, frontend, and clean Compose/API CI jobs; this evidence is not a production security review. See `docs/security/threat-model.md` for the scoped local threat model and residual risks. No performance, financial, underwriting, settlement, or compliance metrics are claimed.
