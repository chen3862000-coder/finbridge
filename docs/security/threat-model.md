# Local Demo Threat Model

## Scope

This threat model covers only the localhost Compose demonstration: browser, HTTPS reverse proxy, Spring demo API, Keycloak, PostgreSQL, and trusted synthetic provider JARs. It is a concise design aid, not a penetration test, security certification, or production threat assessment.

## Assets

- Synthetic applicant, loan, workflow, payment, and tenant data in the local database.
- Server-side OIDC sessions and issuer/subject-to-tenant membership mappings.
- Plugin artifact identity, tenant capability bindings, lifecycle actions, and payment ownership records.
- Local TLS private key, Keycloak bootstrap account, seeded demo passwords, and database password.

All credentials and records are public synthetic development fixtures. They must not be reused for personal, production, or shared environments.

## Trust Boundaries

| Boundary | Trusted input / control | Main limitation |
|---|---|---|
| Browser to reverse proxy | Locally generated HTTPS; same-origin application/API; secure session cookie and CSRF validation | The self-signed local certificate is not a public trust configuration. E2E disables certificate validation only for this local fixture. |
| Reverse proxy to API/Keycloak | Compose-internal service network and explicit forwarded host/protocol headers | Forwarded headers are trusted only in the documented local topology. Do not expose the API or proxy behind arbitrary intermediaries without configuring trusted proxy addresses. |
| OIDC identity to FinBridge principal | Authorization Code + PKCE, state/nonce/token validation, configured issuer, server-side issuer/subject membership lookup | Local Keycloak is a test identity provider, not an enterprise identity service. Membership administration and key lifecycle are not production hardened. |
| API to PostgreSQL | Fixed server-side datasource configuration and parameterized persistence operations | The local database password and database port are development fixtures. No production secret-management or network policy is supplied. |
| Host to provider JAR | Allowlisted local artifact path, descriptor/SPI validation, checksum identity, per-artifact class loader | Provider code is trusted in-process Java with host privileges. Class loaders do not sandbox code or bound its CPU, memory, filesystem, or network access. |

## Threats and Mitigations

| Threat | Existing mitigation | Residual risk |
|---|---|---|
| Caller spoofs tenant or borrower identity | Tenant and applicant scope derive from server-side membership and the trusted `FinBridgePrincipal`; borrower ownership is checked per object | Production identity lifecycle, membership governance, and independent authorization assessment are not provided. |
| Cross-tenant/object-ID access | Tenant-scoped repositories and borrower ownership checks; denial is covered by HTTP and E2E tests | Only the implemented demo routes and scenarios have evidence; this is not a complete application security assessment. |
| CSRF or session misuse | Same-origin BFF, server-side session, PKCE, CSRF on state-changing requests, HttpOnly/Secure/SameSite cookie, session fixation protection, logout | Session storage is single-host. Key rotation, revocation operations, and production session-store protections remain open. |
| Clickjacking, content injection, or browser capability abuse | CSP, frame denial, MIME sniffing protection, restrictive referrer policy, and Permissions Policy at the reverse proxy | The development Vite server and its WebSocket/HMR behavior are intended for localhost only. CSP must be revisited if external scripts/styles/assets are introduced. |
| Malicious or compromised plugin | Allowlisted local artifacts, SHA-256 identity checks, SPI/descriptor/capability validation, no browser upload | These checks are not signatures or sandboxing. A malicious trusted JAR can execute with host process privileges. |
| Plugin hangs during init, health, or close | Bounded dedicated lifecycle executors, callback deadlines, quarantine diagnostics, and non-cooperative real-JAR tests | Interrupt is cooperative only. A callback that ignores interruption can continue on a daemon thread; use process isolation for untrusted or hard-availability requirements. |
| Forwarded-header spoofing | Compose topology routes through the local Nginx proxy and the API is not published as a host port | Spring forwarded-header processing must only sit behind a controlled proxy. Do not set wildcard trusted proxies in a general deployment. |
| Local credential/TLS exposure | `.env`, private keys, truststores, and local backups are ignored; Compose binds published service ports to loopback where applicable | Seeded credentials and local database defaults are visible in source by design. Never expose the stack to an untrusted network. |
| Ambiguous external payment outcome | Durable idempotency/owner records and query-only reconciliation through the original provider | The provider is synthetic. The design does not establish real settlement or exactly-once external effects. |

## Deployment Assumptions

- Compose services are for a developer workstation, not an internet-facing deployment.
- Only synthetic test accounts/data and synthetic plugin providers are used.
- The operator trusts all provider JARs copied into the approved plugin directory.
- The reverse proxy is the only browser-facing HTTPS endpoint; API and Keycloak internal services remain on the Compose network.
- Local backups, TLS keys, and generated state remain private and untracked.

## Out of Scope Before Production

Production use requires a separate threat model and security review covering identity provider selection and operations, tenant lifecycle and authorization policy, secrets and key rotation, proxy/network trust, plugin signing or process isolation, audit retention, monitoring/incident response, data privacy, availability, disaster recovery, and real provider/payment threat scenarios.
