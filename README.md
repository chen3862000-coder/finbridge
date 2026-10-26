# FinBridge

**One Core. Multiple Providers. Zero Core Changes.**

FinBridge is a synthetic, multi-tenant lending and financial integration platform. M0 has been reviewed; M1 Plugin Foundation and M2 Lending Core are implemented. Risk provider integration and approval policy remain M3 scope.

Start with the [v2 architecture proposal](docs/architecture/m0-proposal-v2.md), then review the [M1 System Overview](docs/architecture/01-system-overview.md), [SPI Design](docs/architecture/02-spi-contract-design.md), [ClassLoader Isolation](docs/architecture/03-classloader-isolation.md), [Plugin Lifecycle](docs/architecture/04-plugin-lifecycle.md), [Lending Domain Model](docs/architecture/02-lending-domain-model.md), and [Repayment Plan Design](docs/architecture/06-repayment-plan-design.md). The [v1 proposal](docs/architecture/m0-proposal.md) is superseded history.

Build and run the quality gates using JDK 21: `./mvnw clean verify`. M1 packages independent AlphaPay v1/v2 provider JARs under their plugin module `target/` directories; the host composition artifact has no concrete provider dependency.

Coding-agent constraints and blocking quality gates are documented in [`AGENTS.md`](AGENTS.md) and [`docs/quality/quality-gates.md`](docs/quality/quality-gates.md). Before approving M0, review the M0-specific coverage behavior: isolated spike Java sources are deliberately not product modules, so the product coverage floor is enforced when M1 modules and their tests are added rather than used to gate M0 spikes.

All providers and business data will be synthetic. No real payment, credit bureau, or customer data integrations are intended.
