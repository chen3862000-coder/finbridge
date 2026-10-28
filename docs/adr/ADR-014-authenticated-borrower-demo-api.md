# ADR-014: Authenticated Borrower Demo API

- **Status:** Accepted; local demo implementation verified, M7 release documentation remains open
- **Date:** 2026-10-28

## Context

M6 provides a static Console and Spring MVC adapters, but the repository does not include a runnable API host or an HTTP identity provider. A local demo must let synthetic borrowers submit loan applications and query approval/workflow, loan, repayment, and payment status without allowing users to see or change another tenant's data. M5 ADR-005 establishes trusted, server-derived tenant identity in host facades and explicitly leaves credential issuance, HTTP filters, and external identity to the containing application.

## Decision

Implement an M7-only, authenticated borrower API demo using the following boundary:

- Add a runnable Spring Boot API composition for the existing host adapters, persistence, migrations, Spring Security, and plugin runtime. Keep borrower routes separate from admin plugin-management routes.
- Use Keycloak 26.3.5 as the standards-conformant local OpenID Connect provider for synthetic demo identities. This product selection is local-demo-only and does not select a production identity provider. The browser uses Authorization Code flow with PKCE. Spring Security performs the configured OIDC ID-token and authorization-response validation using the configured issuer and key set. The local provider, seeded accounts, and configuration are demo infrastructure only.
- Use a same-origin Backend-for-Frontend (BFF). Keep OIDC tokens server-side and issue an opaque session in a `Secure`, `HttpOnly`, `SameSite` cookie. Protect state-changing cookie-authenticated requests with CSRF tokens, use restrictive same-origin CORS and security headers, and terminate HTTPS at the local development reverse proxy. Do not store access/refresh tokens in browser storage or place credentials/secrets in the frontend or source control.
- After authentication, map the validated `(issuer, subject)` to an enabled demo user and tenant membership held in server-owned persistence/configuration. Construct the trusted `FinBridgePrincipal` from that mapping. Derive every tenant operation from the principal; reject caller-selected tenant IDs and cross-tenant/object ownership access. Unknown, disabled, or unmapped identities are denied by default. Map admin membership separately and continue to require `ROLE_FINBRIDGE_ADMIN`.
- Expose only borrower functions needed for the demo: list products, submit an application with an idempotency key and exact decimal-string amount, and read the caller's application, synthetic approval/risk/workflow status, loan, repayment plan, and disbursement status. Do not expose plugin management, provider credentials, raw risk evidence, or records outside the principal's tenant.
- Compose the API, BFF/Console, PostgreSQL, the selected local OIDC provider, and local HTTPS reverse proxy for the demo. Use only synthetic identities/data and synthetic providers. Do not trigger real provider effects.

## Verification

Verified evidence: the repeatable live HTTP E2E uses the real local Keycloak login and callback, asserts PKCE S256, mapped borrower/admin sessions, CSRF-protected application/risk/origination/disbursement/reconciliation operations, exact decimal transport, cross-tenant object-ID denial, and mutually exclusive borrower/admin route access. PostgreSQL-backed MockMvc tests cover security filters, missing membership, CSRF, object ownership, and both route-role directions. Unit tests cover issuer mismatch and missing membership in the OIDC user mapper. The automated suite does not yet inject malformed, expired, tampered, wrong-audience, or wrong-nonce OIDC tokens through the live callback, nor verify session expiry and logout over the live E2E. Do not claim those cases as tested. A test that directly installs `FinBridgePrincipal` into `SecurityContext` alone is not authentication-boundary evidence.

Run the full Compose smoke gate and authenticated HTTP E2E gate from `AGENTS.md`. The latter must not be claimed until the runnable API and OIDC/BFF services are started and tested over HTTP.

## Consequences and Limits

- ADR-005 remains the host facade authorization rule: tenant identity is trusted only after server-side authentication and is never client-selected. This ADR adds the M7 demo HTTP identity/session composition; it does not weaken or replace that rule.
- The local OIDC provider proves the demo integration path only. Local accounts, keys, certificates, and trust configuration are not production credentials or a production security recommendation. The local E2E explicitly disables TLS certificate verification for its generated self-signed certificate; this is not acceptable outside this isolated demo test.
- TLS is required for the local demo route; the development certificate/trust instructions must be explicit. No real personal data or provider effects are permitted.
- The runnable API and identity stack add M7 scope and must be included in clean-clone/Compose verification and the architecture/quality traceability record.
- Existing single-host lifecycle, recovery, and session limitations remain. This decision does not add multi-host guarantees, production identity federation, validated underwriting, or payment-provider security certification.

## Revisit Conditions

Revisit before any deployment beyond the synthetic local demo, if the identity/session topology changes, or if multiple API hosts are introduced. A production deployment requires a separately reviewed identity provider, secrets/key management, TLS termination, session store, threat model, and operational security design.
