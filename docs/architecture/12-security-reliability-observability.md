# Security, Reliability, and Observability

## Security Boundaries

Host tenant operations require a trusted server-created `FinBridgePrincipal`; admin lifecycle operations require `ROLE_FINBRIDGE_ADMIN`. In the local demo, Spring Security performs OIDC login using Authorization Code + PKCE, a server-side session, CSRF token handling, secure session cookies, and issuer/subject membership mapping. Borrower and administrator authorities are mutually exclusive. Nginx terminates local HTTPS and proxies API, OIDC, and Console paths on one origin.

All included users, applicants, products, risk results, and providers are synthetic. Keycloak passwords are local fixtures. Generated self-signed certificates and the Node E2E's disabled certificate validation are local-demo-only. Do not copy them into a production deployment.

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

The recorded Java and local HTTP gates cover implemented paths. Wrong-audience/tampered/expired/nonced token callback cases, live session expiry/logout, and production threat cases remain verification gaps. The local E2E only proves successful OIDC login and the listed demo flows.
