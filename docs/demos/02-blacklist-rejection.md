# Demo 2: Blacklist Rejection

## Goal

Show that a normalized synthetic blacklist hit is a core-owned rejection and cannot create a loan, repayment plan, or payment.

## Behavior Fixture

`BlacklistYProvider` returns a synthetic hit when the synthetic applicant reference contains `blacklisted`. The demo API currently fixes the applicant reference to the authenticated `self` identity, so the public borrower E2E does not currently select this fixture from the browser. Do not add a caller-controlled applicant/tenant identity to reach it.

## Verification Command and Expected Result

Under JDK 21, run:

```sh
./mvnw --batch-mode --no-transfer-progress -pl finbridge-risk-gateway -am test
```

`SyntheticRiskDecisionPolicyTest.blacklistAndCriticalFraudRejectBeforeScoreBand` should pass and report `REJECT` with `BLACKLIST_HIT`; the lending workflow tests verify rejection does not originate a loan/payment. The root `clean verify` has passed these tests. This is module-level evidence, not a separately executed HTTP walkthrough.

## Evidence

- Provider behavior: `plugins/blacklisty/src/main/java/io/github/finbridge/plugins/blacklisty/BlacklistYProvider.java`
- Policy test: `SyntheticRiskDecisionPolicyTest` blacklist precedence case
- Workflow persistence tests: `JdbcLendingCoreIntegrationTest.durableRiskWorkflowClaimsAndCompletesAtomicallyWithDecision`
- Related design: ADR-012 and ADR-014

## Verification Status

The deterministic policy and workflow rules are covered at module level. A standalone live borrower HTTP walkthrough for this rejection is **not yet implemented or executed**. Do not report the seven-demo release requirement as complete on this basis.
