# Provider SPI Contracts

The SPI is the stable integration boundary between Lending Core/gateways and separately packaged providers. It is Java/framework-neutral and excludes Spring, JDBC, HTTP, and host-service types.

## Contract Shape

- `FinBridgePlugin` provides a descriptor and optional initialize, health, and close lifecycle hooks.
- `PaymentProvider` supports disbursement, collection, and status query using typed requests and operation context.
- `RiskDataProvider` advertises one risk-data type and returns typed evidence; it does not make lending decisions.
- `PluginDescriptor` declares ID/version, SPI version, provider type, and capabilities.
- `PaymentStatus` includes `PROCESSING`, `SUCCEEDED`, `FAILED`, and `UNKNOWN`; unknown is not a definitive failure.

Providers register through Java `ServiceLoader` descriptors in `META-INF/services`. Runtime validates descriptor/provider agreement and required capabilities before normal activation. SPI major version compatibility is checked at load time; semantic compatibility beyond current contract tests is not claimed.

## Rules for Providers

1. Return only the capability and data types declared by the descriptor.
2. Keep provider-specific DTOs and dependencies inside the provider artifact.
3. Use host-supplied operation identity/deadline and idempotency references.
4. Report uncertainty as `UNKNOWN`, never as `FAILED` merely because the caller timed out.
5. Treat initialize/health/close callbacks as bounded by host deadlines; interruption may not stop arbitrary code.

See [Create a Synthetic Provider](../guides/create-provider.md), [Class-Loader Isolation](05-classloader-isolation.md), and source contracts under `finbridge-spi`.
