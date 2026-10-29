# Security, Reliability, and Observability

## Security Boundaries

Host tenant operations require a trusted server-created `FinBridgePrincipal`; admin lifecycle operations require `ROLE_FINBRIDGE_ADMIN`. In the local demo, Spring Security performs OIDC login using Authorization Code + PKCE, a server-side session, CSRF token handling, secure session cookies, and issuer/subject membership mapping. Borrower and administrator authorities are mutually exclusive. `/api/session` returns only an anonymous/authenticated status view and is public so the sign-in page can render; protected data/API routes still require their mapped roles. Nginx terminates local HTTPS and proxies API, OIDC, and Console paths on one origin.

All included users, applicants, products, risk results, and providers are synthetic. Keycloak passwords are local fixtures. Generated self-signed certificates and the Node E2E's disabled certificate validation are local-demo-only. Do not copy them into a production deployment.

Seeded risk demo identities map server-side to fixed synthetic applicant references for review-band, blacklist-hit, and critical-fraud fixtures. Borrower application requests cannot select a scenario or applicant ID; tenant identity and fixture are derived from membership mapping.

Provider class loaders isolate dependencies but do not sandbox executing provider code. No production identity provider, token-key operations, secrets manager, threat model, deployment hardening, or production-ready security review is claimed.

## Reliability and Observability

Provider calls have bounded timeouts/execution and typed failure outcomes. Durable application, workflow, payment and plugin lifecycle state uses PostgreSQL. Startup recovery and in-memory runtime publication are single-host. Micrometer counters/timers use bounded operation/outcome labels rather than tenant/application/payment IDs.

## Evidence

- `finbridge-host/src/main/java/io/github/finbridge/host/HostAuthorization.java`
- `finbridge-plugin-runtime/src/main/java/io/github/finbridge/runtime/PluginOperationMetrics.java`
- `finbridge-risk-gateway/src/main/java/io/github/finbridge/risk/RiskDataGateway.java`
- `finbridge-demo-app/src/main/java/io/github/finbridge/demo/DemoSecurityConfiguration.java`
- `finbridge-demo-app/src/main/java/io/github/finbridge/demo/DemoOidcUserService.java`
- `finbridge-demo-app/src/main/java/io/github/finbridge/demo/DemoIdentityFilter.java`
- `finbridge-demo-app/src/test/java/io/github/finbridge/demo/DemoSecurityHttpTest.java`
- `finbridge-demo-app/src/test/java/io/github/finbridge/demo/DemoOidcUserTest.java`
- `finbridge-plugin-runtime/src/test/java/io/github/finbridge/runtime/PluginRuntimeValidationTest.java`
- ADR-005, ADR-006, ADR-008, ADR-014

The recorded Java and local HTTP gates cover implemented paths. Live E2E verifies session cookie `Secure`, `HttpOnly`, and `SameSite=Lax` attributes, CSRF-protected logout, post-logout admin denial, caller-supplied conflicting tenant/applicant/role/risk-scenario values not overriding server mappings, rejection of an uncorrelated OAuth state and invalid authorization code, a nonce altered in the outbound request followed by a real Keycloak-signed ID token that remains unauthenticated, and server-side session expiry under a short test override. Focused tests exercise Spring Security's real OIDC authorization-code provider and Nimbus ID-token decoder with signed synthetic tokens, rejecting malformed, expired, not-yet-valid, wrong-issuer, wrong-audience, untrusted-signature, and nonce-mismatched tokens. Expired/forged/wrong-claim tokens have not each been run through live Keycloak callbacks. Production threat cases remain unverified. The local E2E explicitly disables TLS certificate checks for the self-signed local certificate and must remain local-only.
