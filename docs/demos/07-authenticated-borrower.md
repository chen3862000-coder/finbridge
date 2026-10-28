# Demo 7: Authenticated Synthetic Borrower

## Goal

Exercise the approved local OIDC and public borrower API boundary over HTTP.

## Run

Follow `docs/guides/getting-started.md`, then from `frontend/`:

```sh
FINBRIDGE_DEMO_ALLOW_INSECURE_TLS=true npm run test:e2e
```

## Expected Behavior

The live E2E checks Keycloak Authorization Code + PKCE, alpha application creation using exact decimal text, synthetic risk approval, workflow status, loan origination, inactive-before-disbursement plan, one successful synthetic disbursement, active loan/plan, payment status and reconciliation, beta denial for alpha's application, borrower denial to admin routes, and admin denial to borrower routes while allowing the admin registry route.

## Evidence

- Executable: `scripts/demo-http-e2e.mjs`
- HTTP security tests: `DemoSecurityHttpTest`
- Mapping/role tests: `DemoOidcUserTest`
- Related design: ADR-014 and `docs/architecture/12-security-reliability-observability.md`
- Actual result: M7 Demo Review Record in `docs/quality/architecture-review-checklist.md`

## Limits

The script disables TLS verification only for the generated self-signed local certificate. It does not cover malformed/expired/tampered/wrong-audience token callbacks, logout/session expiry, production identity, or real provider effects.
