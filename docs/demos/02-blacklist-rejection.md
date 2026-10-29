# Demo 2: Blacklist Rejection

## Goal

Show that a normalized synthetic blacklist hit is a core-owned rejection and cannot create a loan, repayment plan, or payment.

## Behavior Fixture

`BlacklistYProvider` returns a synthetic hit when the server-generated synthetic applicant reference contains `blacklisted`. Sign in as `borrower-alpha-blacklist` / `alpha-blacklist-local-only`. Its issuer/subject mapping assigns that fixed applicant reference server-side. The borrower API does not accept an applicant, tenant, or scenario selector.

## Verification Command and Expected Result

Under JDK 21, run:

```sh
./mvnw --batch-mode --no-transfer-progress -pl finbridge-risk-gateway -am test
```

`SyntheticRiskDecisionPolicyTest.blacklistAndCriticalFraudRejectBeforeScoreBand` reports `REJECT` with `BLACKLIST_HIT`; PostgreSQL workflow tests verify rejection does not originate a loan/payment. The live E2E additionally signs in as the dedicated blacklist fixture identity and verifies rejection through HTTP.

## Evidence

- Provider behavior: `plugins/blacklisty/src/main/java/io/github/finbridge/plugins/blacklisty/BlacklistYProvider.java`
- Policy test: `SyntheticRiskDecisionPolicyTest` blacklist precedence case
- Workflow persistence tests: `JdbcLendingCoreIntegrationTest.durableRiskWorkflowClaimsAndCompletesAtomicallyWithDecision`
- Related design: ADR-012 and ADR-014

## Verification Status

Verified 2026-10-29 with `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export PATH="$JAVA_HOME/bin:$PATH"; ./mvnw --batch-mode --no-transfer-progress -pl finbridge-risk-gateway -am test`: BUILD SUCCESS, 14 tests passed in the Risk Data Gateway module and its dependency reactor. The live HTTP walkthrough was run against a freshly reset synthetic Compose database with `cd frontend && FINBRIDGE_DEMO_ALLOW_INSECURE_TLS=true npm run test:e2e`; its dedicated blacklist identity received `REJECT`/`REJECTED` and created no loan or payment. The script runs the complete demo suite, not only this scenario. This local synthetic path is not a real credit decision.
