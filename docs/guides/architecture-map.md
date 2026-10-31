# Architecture Map

## Runtime Direction

`finbridge-spi` defines framework-neutral plugin and typed risk/payment contracts. Provider implementation modules under `plugins/` depend on the SPI and build external assembly JARs. `finbridge-plugin-runtime` discovers providers, creates per-artifact class loaders, manages plugin lifecycle and invocation leases, and routes through tenant capability bindings. `finbridge-lending-core` owns application, risk workflow, loan, repayment, and payment persistence/state transitions. `finbridge-risk-gateway` and `finbridge-payment-gateway` adapt typed core requests to routed provider calls. `finbridge-host` composes persistence, principal/role authorization, lifecycle control, recovery, and Console adapters. `finbridge-demo-app` is the runnable local Spring Boot composition; it is not the host library itself.

The root `pom.xml` aggregates Maven modules but does not make all modules runtime dependencies. Host package tests check that concrete providers are absent. The runnable demo image puts provider fixture JARs under `/app/plugins`, outside the application classpath.

## Trust and Transaction Boundaries

- Provider artifacts are trusted in-process code. Class-loader isolation is dependency isolation, not a sandbox.
- Tenant identity is derived from the trusted `FinBridgePrincipal`; local demo OIDC subject mapping is stored server-side.
- Application and provider workflow state is durable in PostgreSQL. Provider calls occur outside local DB transactions.
- Payment ownership is pinned to the original plugin/version. Unknown outcomes are reconciled, never blindly resubmitted.
- Loan and repayment plan are activated only after confirmed successful disbursement is persisted.

## Architecture Reading Path

Start with the [platform specification](../architecture/01-platform-spec.md), then use the [architecture guide](../architecture/README.md) to navigate domain and runtime topics.

## Source References

- `finbridge-spi/src/main/java/io/github/finbridge/spi/`
- `finbridge-plugin-runtime/src/main/java/io/github/finbridge/runtime/`
- `finbridge-lending-core/src/main/java/io/github/finbridge/lending/`
- `finbridge-risk-gateway/src/main/java/io/github/finbridge/risk/`
- `finbridge-payment-gateway/src/main/java/io/github/finbridge/payment/`
- `finbridge-host/src/main/java/io/github/finbridge/host/`
- `finbridge-demo-app/src/main/java/io/github/finbridge/demo/`
- [Architecture documents](../architecture/README.md)
