# Risk Decision and Data Gateway

## Ownership

Risk providers implement `RiskDataProvider` from `finbridge-spi`. Providers return typed `RiskDataResult` values; they do not approve or reject an application. `RiskDataGateway` routes each data type through the tenant's capability binding, calls the provider with a bounded executor/timeout, and returns a normalized result or failure code. Provider calls occur outside lending database transactions.

`RiskAssessmentService` gathers credit score, blacklist, and fraud-signal results, stores provider execution references, normalizes missing/invalid values, and delegates the decision to `SyntheticRiskDecisionPolicy` in the core-owned risk gateway module. The policy is synthetic and for demonstration only. It is not validated underwriting.

## Failure Policy

- A blacklist hit and a matching critical fraud signal are deterministic rejection conditions.
- Missing mandatory blacklist or credit-score evidence yields review rather than approval.
- Missing optional fraud evidence does not alone block approval when mandatory evidence is clear; the reason code records the outage.
- Provider timeout, saturation, exception, missing binding, invalid response, and type mismatch are represented as typed unavailable/invalid outcomes and handled by the core policy.

## Plugin Isolation Limits

Provider implementations are assembled into external JARs and are absent from Lending Core and the host runtime classpath. `PluginClassLoader` gives each artifact a loader while sharing the SPI package with the parent. This is dependency isolation, not a security sandbox: provider code remains trusted in-process code.

## Evidence

- `finbridge-risk-gateway/src/main/java/io/github/finbridge/risk/RiskDataGateway.java`
- `finbridge-risk-gateway/src/main/java/io/github/finbridge/risk/SyntheticRiskDecisionPolicy.java`
- `finbridge-lending-core/src/main/java/io/github/finbridge/lending/application/RiskAssessmentService.java`
- `plugins/creditscorex`, `plugins/blacklisty`, `plugins/fraudsignalz`
- `finbridge-risk-gateway/src/test/java/io/github/finbridge/risk/RiskDataGatewayIntegrationTest.java`
- `finbridge-risk-gateway/src/test/java/io/github/finbridge/risk/RiskDataGatewayFailureTest.java`
- `finbridge-risk-gateway/src/test/java/io/github/finbridge/risk/SyntheticRiskDecisionPolicyTest.java`
- `finbridge-plugin-runtime/src/test/java/io/github/finbridge/runtime/PluginRuntimeRiskIntegrationTest.java`
- ADR-012, ADR-013

The recorded JDK 21 root `clean verify` passes these deterministic policy, real-artifact routing, provider failure, and PostgreSQL persistence tests. No real bureau or customer data is used.
