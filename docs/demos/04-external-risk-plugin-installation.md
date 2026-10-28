# Demo 4: External Risk Plugin Installation

## Goal

Demonstrate risk providers are separate artifacts discovered at runtime rather than Lending Core dependencies.

## Evidence

- Provider artifacts: `plugins/creditscorex`, `plugins/blacklisty`, `plugins/fraudsignalz`
- Runtime: `PluginRuntime.installRiskProvider`, `PluginDiscovery.discoverRiskProvider`
- Tests: `PluginRuntimeRiskIntegrationTest`, `RiskDataGatewayIntegrationTest.loadsAllRiskPluginsAsExternalJarsAndReturnsTypedSyntheticValues`, `HostPackagingTest`
- Related design: `docs/architecture/03-classloader-isolation.md`, `docs/architecture/04-risk-decision-and-data-gateway.md`

## Verification Command and Expected Result

Under JDK 21, run:

```sh
./mvnw --batch-mode --no-transfer-progress -pl finbridge-plugin-runtime,finbridge-risk-gateway -am test
```

The tests should discover the separately assembled synthetic risk provider artifacts and return typed values. The root `clean verify` has passed those integration tests. This does not demonstrate a public runtime installation endpoint.

## Verification Status

Real JAR loading is covered by integration tests. The local Compose demo installs allowlisted fixture JARs during the opt-in bootstrap. A standalone operator walkthrough proving install without host rebuild/restart is **not yet documented or executed**; do not imply hot install is exposed through a public demo API.
