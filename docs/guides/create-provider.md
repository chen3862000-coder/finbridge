# Create a Synthetic Provider

## Contract and Module

Provider implementations live in their own Maven module and depend on `finbridge-spi` with `provided` scope. Do not add a concrete provider dependency to `finbridge-host`, Lending Core, or the runnable demo API. Choose the typed contract that matches the capability:

- Payment: `PaymentProvider`, `DisbursementRequest`, `PaymentResult`, and status-query contract.
- Risk: `RiskDataProvider`, `RiskDataRequest`, `RiskDataResult`, and one `RiskDataType`.

Return normalized typed values and stable diagnostic codes. Do not return credentials, raw customer records, floating-point money, or policy decisions from a risk provider. Risk providers only supply evidence; the core-owned decision policy owns approval.

## Build an External Artifact

Follow the module pattern under `plugins/creditscorex`, `plugins/blacklisty`, or `plugins/alphapay-v1`. Add a Java `ServiceLoader` descriptor under `src/main/resources/META-INF/services/` for the shared SPI provider interface. Assemble the provider and its private dependencies into an external JAR. Keep private helper packages out of the parent-loaded SPI namespace.

Build and verify from the root with JDK 21:

```sh
./mvnw --batch-mode --no-transfer-progress -pl plugins/creditscorex -am clean verify
```

For architectural claims, load the assembled JAR through `PluginRuntime` with a provider-free parent loader, as the runtime/risk integration tests do. A unit test of the provider class alone does not demonstrate artifact discovery or class-loader isolation.

## Validation and Limits

Validate descriptor version, provider type, supported capability, provider health, and typed response. Add deterministic synthetic tests for normal, unavailable, invalid, timeout, and exception results as applicable. Plugin class-loader separation is not sandboxing; only trusted code belongs in this in-process runtime.
