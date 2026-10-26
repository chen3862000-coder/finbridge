# SPI Contract Design: M1

The stable shared API is in `finbridge-spi` and has no framework dependency. A plugin exposes a provider through the matching `META-INF/services` descriptor and implements a typed gateway contract. The runtime supplies the exact parent-loaded SPI interface `Class` to `ServiceLoader`, preserving JVM type identity across plugin loaders.

## Base Contract and Descriptor

`FinBridgePlugin` defines `descriptor`, optional `initialize`, optional `healthCheck`, and `close`. `PluginDescriptor` carries plugin identity (`pluginId + pluginVersion`), provider family, SPI API version, type, declared capabilities, display name, and description. The artifact key is `PluginKey`; exact artifact bytes are independently identified by SHA-256. Same identity with different bytes is rejected.

Payment API: `PaymentProvider` has typed disburse, collect, and status-query methods. Requests carry UUID references, `BigDecimal` amounts, `Currency`, and idempotency keys; normalized statuses include `PROCESSING`, `SUCCEEDED`, `FAILED`, and `UNKNOWN`. Risk API: `RiskDataProvider` declares one `RiskDataType` and returns `RiskDataResult` with typed sealed values for credit score, blacklist, or fraud signal. It returns data only; it does not decide approval.

`OperationContext` contains execution/correlation identity and a deadline. The host owns execution policy and routing. Contracts deliberately omit Spring, JDBC, web request, provider-specific, and arbitrary map-operation types.

## Compatibility and Failure Behavior

The runtime currently accepts SPI major 1, parsed from the descriptor version. It rejects invalid descriptors, unsupported majors, type/capability disagreement, capability disagreement with a typed risk provider, absent/multiple providers, malformed SPI entries, protected SPI classes in plugin archives, and initialization/health-check failures before registration. Discovery and initialization restore the original thread context class loader in `finally`.

Evidence: `PluginContractsTest`, `PluginRuntimeValidationTest`, and actual provider JAR loading in `PluginRuntimeIntegrationTest`. SPI DTO additions/removals and semantic compatibility policy beyond the initial major are not yet automated; future contract-version fixtures must test old plugin artifacts against new hosts.
