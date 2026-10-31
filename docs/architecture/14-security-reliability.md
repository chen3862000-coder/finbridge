# Security, Reliability, and Observability

## Security Boundaries

The host requires a trusted server-created `FinBridgePrincipal` for tenant operations and `ROLE_FINBRIDGE_ADMIN` for plugin controls. The local demo uses Keycloak Authorization Code + PKCE, server-side sessions, CSRF, secure cookies, same-origin HTTPS proxying, and server-side issuer/subject membership mapping. Borrower and administrator authorities are separate. All identities and records are synthetic.

The reverse proxy configures CSP, Permissions Policy, clickjacking/MIME/referrer protections, and local TLS. Forwarded-header handling assumes the documented controlled Compose proxy topology; it must not be copied as wildcard proxy trust into a general deployment. Seeded passwords, database defaults, local certificates, and E2E TLS bypasses are local fixtures only.

Provider code is trusted in-process code. Class loaders do not sandbox access to memory, CPU, files, network, or credentials. Lifecycle callbacks have deadlines and are quarantined on timeout, but arbitrary uncooperative code cannot be forcibly terminated safely. See the [local threat model](../security/threat-model.md).

## Reliability and Observability

Provider invocations use bounded execution and typed failure outcomes. Durable application, workflow, payment, artifact, binding, and lifecycle-audit state is stored in PostgreSQL. Recovery and runtime publication are single-host. Micrometer counters/timers use bounded operation/outcome labels rather than tenant/application/payment IDs.

## Limits

This is not a production security review, identity recommendation, multi-host availability design, real provider integration, or settlement guarantee. The [quality checklist](../quality/architecture-review-checklist.md) links tested behavior and residual evidence gaps.
