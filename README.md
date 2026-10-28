# FinBridge

**One Core. Multiple Providers. Zero Core Changes.**

FinBridge is a synthetic, multi-tenant lending and financial integration platform. M1-M6 implementation and the ADR-014 local authenticated borrower demo layer are present. M7 documentation and portfolio release work remains in progress; the demo is not production-ready.

Start with the [v2 architecture proposal](docs/architecture/m0-proposal-v2.md), then review the 13 architecture topics: [System Overview](docs/architecture/01-system-overview.md), [Lending Domain Model](docs/architecture/02-lending-domain-model.md), [Loan Origination Workflow](docs/architecture/03-loan-origination-workflow.md), [Risk Gateway](docs/architecture/04-risk-decision-and-data-gateway.md), [Payment and Disbursement](docs/architecture/05-payment-gateway-and-disbursement.md), [Repayment Plan](docs/architecture/06-repayment-plan-design.md), [SPI Design](docs/architecture/02-spi-contract-design.md), [ClassLoader Isolation](docs/architecture/03-classloader-isolation.md), [Plugin Lifecycle](docs/architecture/04-plugin-lifecycle.md), [Tenant Routing](docs/architecture/10-multi-tenant-routing.md), [Transaction Consistency](docs/architecture/11-transaction-consistency.md), [Security and Reliability](docs/architecture/12-security-reliability-observability.md), and [Production Evolution](docs/architecture/13-production-evolution.md). The [v1 proposal](docs/architecture/m0-proposal.md) is superseded history. The numbered filenames for SPI/classloader/lifecycle retain their original paths; the proposal's documentation plan links to those paths.

Contributor and evidence material: [getting started](docs/guides/getting-started.md), [architecture map](docs/guides/architecture-map.md), [create a provider](docs/guides/create-provider.md), [lifecycle operations](docs/guides/plugin-lifecycle-operations.md), [testing and evidence](docs/guides/testing-and-evidence.md), [demo walkthroughs](docs/demos/), and the [portfolio case study](docs/portfolio/case-study.md). Walkthroughs distinguish live HTTP evidence from module tests; the blacklist, tenant-routing, provider-installation, upgrade/rollback, and class-loader demos do not yet have standalone live walkthrough executions recorded.

Build and run the quality gates using JDK 21: `./mvnw --batch-mode --no-transfer-progress clean verify`. The recorded Java 21 gate passed for the 17-module reactor with 257 tests, zero PMD violations, zero SpotBugs findings, and all production JaCoCo thresholds met. M1 packages independent provider JARs under their plugin module `target/` directories; the host composition artifact has no concrete provider dependency.

Coding-agent constraints and blocking quality gates are documented in [`AGENTS.md`](AGENTS.md) and [`docs/quality/quality-gates.md`](docs/quality/quality-gates.md). Before approving M0, review the M0-specific coverage behavior: isolated spike Java sources are deliberately not product modules, so the product coverage floor is enforced when M1 modules and their tests are added rather than used to gate M0 spikes.

All providers and business data will be synthetic. No real payment, credit bureau, or customer data integrations are intended.

## Authenticated Local Demo

The approved M7 local demo stack runs PostgreSQL, the runnable Spring API, Keycloak OIDC, the Vite Console, and an HTTPS reverse proxy. Generate a development-only self-signed certificate/trust store, then start the services:

```bash
./deploy/generate-local-tls.sh
docker compose up
```

Trust `deploy/tls/localhost.crt` in the local browser/OS trust store, then open `https://localhost:8443/borrower`. Synthetic accounts are `borrower-alpha` / `alpha-local-only`, `borrower-beta` / `beta-local-only`, and `demo-admin` / `admin-local-only`. The borrower identities map server-side to `DEMO_ALPHA` and `DEMO_BETA`; the browser never chooses a tenant. The admin account can use `/console` for internal plugin controls.

For automated authenticated HTTP checks after the cluster is healthy, run `FINBRIDGE_DEMO_ALLOW_INSECURE_TLS=true npm run test:e2e` from `frontend/`. The script uses the locally generated self-signed certificate only for this demo and checks OIDC/PKCE login, exact decimal application submission, synthetic risk decision, origination, repayment-plan lookup, one synthetic disbursement, payment status/reconciliation, tenant isolation, and borrower/admin role separation. The script sets `NODE_TLS_REJECT_UNAUTHORIZED=0`; never use that setting outside this local-only test.

PostgreSQL is available on `localhost:5432` with database/user `finbridge`; the default password is `finbridge-local-only` for local use only. Override settings with a root `.env` file based on `.env.example`. Flyway baselines the existing synthetic schema at V2 and applies V3 identity membership. Existing local volumes are not overwritten. To reset all local demo data and trigger a clean import, use `docker compose down -v` only when intentionally discarding it.

The application image packages providers only as external plugin JAR fixtures under `/app/plugins`; they are not added to the API host classpath. The opt-in Compose demo bootstrap registers/activates and binds synthetic risk/payment fixtures for seeded tenants. This stack is local demo infrastructure only: its passwords and self-signed trust root are not production credentials or certificates. Never use real borrower data or providers here. `FINBRIDGE_DEMO_BOOTSTRAP_PLUGINS` defaults to false and is enabled only by Compose. The demo app baselines an existing schema at Flyway V2 and applies V3. Artifact checksums are immutable: rebuilding changed plugin JARs against an existing volume fails closed rather than replacing registered artifacts. To reinitialize this synthetic-only local database after rebuilding artifacts, intentionally discard its volume with `docker compose down -v`, then restart the stack.

Stop services while preserving data with `docker compose down`; follow logs with `docker compose logs -f`. Full demo startup may take several minutes on first build.
