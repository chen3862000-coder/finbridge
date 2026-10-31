# FinBridge

**One Core. Multiple Providers. Zero Core Changes.**

FinBridge is an educational, synthetic lending-platform demonstration built around an external-provider plugin framework. A stable lending core calls independently packaged risk and payment providers through a typed SPI. The runtime discovers provider JARs, isolates provider-private dependencies, routes capabilities by tenant, and manages activation, draining, upgrades, and rollback without linking concrete providers into the host.

![FinBridge plugin framework: a stable lending core connects through typed gateways to independent, tenant-routed risk and payment providers.](docs/architecture/diagram-plugin-framework.svg)

The framework keeps business policy in the core while provider implementations evolve independently. Add or upgrade a compatible provider at the integration boundary rather than embedding provider-specific code in lending workflows. Persisted payment ownership keeps reconciliation tied to the provider version that began the operation, even when future tenant routing changes.

**Why the plugin design matters:**

- **Keep the core provider-neutral:** typed SPI contracts separate lending decisions and workflows from provider implementations.
- **Evolve integrations independently:** providers are assembled as external JARs and are absent from the host runtime classpath.
- **Route by tenant and capability:** different tenants can bind risk or payment capabilities to different provider versions.
- **Manage change safely:** lifecycle controls drain invocations and support upgrade/rollback; unresolved payment work retains its original provider owner.

See [Create a Synthetic Provider](docs/guides/create-provider.md), [Plugin Lifecycle](docs/architecture/06-plugin-lifecycle.md), and [Tenant Routing](docs/architecture/12-tenant-routing.md) for implementation details.

This is not a production lending, underwriting, identity, or payment system. All applicants, tenants, decisions, transactions, and providers are synthetic. Plugin class-loader isolation is not a sandbox: plugins execute as trusted code in the host process. The runtime is designed for one host process. Do not use real customer data, credentials, or provider integrations.

## What It Demonstrates

- A modular Java 21 application with a stable lending core and provider-neutral SPI.
- External plugin JAR discovery, class-loader isolation, tenant capability routing, lifecycle operations, and rollback.
- Durable application, risk, origination, repayment, and disbursement workflows backed by PostgreSQL.
- Exact decimal amounts, idempotent application/payment claims, immutable payment-provider ownership, and reconciliation of ambiguous outcomes.
- A local React Console and Spring API secured by Keycloak OIDC Authorization Code + PKCE, server-side sessions, CSRF protection, and server-side issuer/subject-to-tenant membership mapping.
- Synthetic borrower and administrator journeys runnable with Docker Compose.

## Quick Start

### Requirements

- JDK 21
- Docker with Docker Compose
- Node.js and npm for frontend checks and the optional HTTP E2E suite
- OpenSSL for generating a local-only TLS certificate

Build and run the Java quality gates:

```sh
./mvnw --batch-mode --no-transfer-progress clean verify
```

Start the local demo:

```sh
./deploy/generate-local-tls.sh
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

Trust the generated `deploy/tls/localhost.crt` in your local browser/OS trust store, then open <https://localhost:8443/borrower>. The certificate and its private key are local development fixtures and must not be used elsewhere.

Use the [local demo guide](docs/guides/borrower-demo-usage.md) for seeded synthetic accounts and the full borrower/admin walkthrough. Borrower pages are scoped to the signed-in synthetic applicant; internal Console views are tenant-wide. Tenant identity and borrower ownership are derived on the server, not from caller-selected request fields.

Stop the stack and preserve its local data with:

```sh
docker compose down
```

The demo uses seeded local-only passwords and a default local database password. They are public fixtures, not secrets suitable for reuse. Never expose this stack to an untrusted network. Compose binds demo services to localhost. To intentionally discard all synthetic database data and volumes, use `docker compose down -v`.

## Run Checks

Java checks run from the repository root with JDK 21:

```sh
./mvnw --batch-mode --no-transfer-progress clean verify
```

Frontend checks run from `frontend/`:

```sh
npm ci
npm run build
npm test
npm audit --omit=dev
```

The audit command checks production dependencies only. The authenticated live HTTP suite targets the local Compose stack and deliberately disables Node TLS verification for its generated self-signed certificate. Run it only against that isolated local demo:

```sh
FINBRIDGE_DEMO_ALLOW_INSECURE_TLS=true npm run test:e2e
```

See [Testing and Evidence](docs/guides/testing-and-evidence.md) for scope and evidence boundaries.

## Architecture and Guides

- [Architecture guide](docs/architecture/README.md)
- [Full platform specification](docs/architecture/01-platform-spec.md)
- [Domain-driven design](docs/architecture/02-domain-driven-design.md)
- [System architecture](docs/architecture/03-system-architecture.md)
- [Provider SPI contracts](docs/architecture/04-spi-contracts.md)
- [Class-loader isolation](docs/architecture/05-classloader-isolation.md)
- [Plugin lifecycle](docs/architecture/06-plugin-lifecycle.md)
- [Lending domain](docs/architecture/07-lending-domain.md)
- [Origination workflow](docs/architecture/08-origination-workflow.md)
- [Risk assessment](docs/architecture/09-risk-assessment.md)
- [Payment and disbursement](docs/architecture/10-payment-disbursement.md)
- [Repayment schedule](docs/architecture/11-repayment-schedule.md)
- [Tenant capability routing](docs/architecture/12-tenant-routing.md)
- [Transaction consistency](docs/architecture/13-transaction-consistency.md)
- [Security and reliability](docs/architecture/14-security-reliability.md)
- [Production evolution and limits](docs/architecture/15-production-evolution.md)
- [Getting started](docs/guides/getting-started.md)
- [Create a provider](docs/guides/create-provider.md)
- [Plugin lifecycle operations](docs/guides/plugin-lifecycle-operations.md)
- [Local borrower demo and default tenant capability bindings](docs/guides/borrower-demo-usage.md)
- [Demo walkthroughs](docs/demos/)
- [Portfolio case study and demo screenshots](docs/portfolio/case-study.md)
- [Architecture decision records](docs/adr/README.md)

The current platform specification describes the implemented system and its explicit limits. Individual accepted design decisions remain recorded in [ADRs](docs/adr/README.md); see the [architecture review checklist](docs/quality/architecture-review-checklist.md) for implementation-to-test traceability.

## Project Status and Limitations

The repository is a local synthetic demonstration. It demonstrates selected web controls but is not production-ready or a production security recommendation. Production identity selection, deployment hardening, secrets management, multi-host guarantees, real risk underwriting, and real payment settlement are out of scope. See [Security and Reliability](docs/architecture/14-security-reliability.md), the [local demo threat model](docs/security/threat-model.md), [Production Evolution and Limits](docs/architecture/15-production-evolution.md), and the [portfolio case study](docs/portfolio/case-study.md) for limitations and evidence.

## License

This project is distributed under the MIT License. See [LICENSE](LICENSE). Third-party dependencies and assets remain subject to their own licenses.
