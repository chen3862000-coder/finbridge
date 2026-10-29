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

The tests discover the separately assembled synthetic risk provider artifacts and return typed values. In addition, the live admin E2E installs FraudSignalZ from the prebuilt `/app/plugins` artifact directory, activates it, and binds `FRAUD_SIGNAL` to the alpha tenant through the authenticated admin HTTP API. The executable command/result is recorded in the M7 review record. The install call does not rebuild or restart the host. This uses only an allowlisted synthetic fixture and a local admin account.

## Verification Status

Verified 2026-10-29 with `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export PATH="$JAVA_HOME/bin:$PATH"; ./mvnw --batch-mode --no-transfer-progress -pl finbridge-plugin-runtime,finbridge-risk-gateway -am test`: BUILD SUCCESS, including 25 plugin-runtime tests and 14 risk-gateway tests. The live walkthrough ran against a fresh synthetic Compose stack with `cd frontend && FINBRIDGE_DEMO_ALLOW_INSECURE_TLS=true npm run test:e2e`; observed pass: `External risk plugin installation E2E passed: FraudSignalZ installed and bound without host rebuild`. FraudSignalZ is intentionally omitted from bootstrap; the admin API installs the allowlisted JAR from `/app/plugins`, activates it, and binds `FRAUD_SIGNAL`. No arbitrary upload endpoint is exposed. The HTTP command runs the complete demo suite, not only this scenario.
