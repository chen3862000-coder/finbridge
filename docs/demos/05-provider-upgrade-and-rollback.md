# Demo 5: Provider Upgrade and Rollback

## Goal

Show upgrades change future routing while preserving historical transaction ownership and blocking unsafe unload.

## Evidence

- Lifecycle: `PluginLifecycleManager.upgrade`, `rollback`, `drainRetired`
- Tests: `PluginLifecycleManagerTest.upgradeCommitsFutureBindingRevisionAndRetiresPreviousOwner`, rollback and retained-owner cases; `PluginControlPlaneIntegrationTest`
- Related design: ADR-006 and ADR-007

## Verification Command and Expected Result

Under JDK 21 with Docker available for PostgreSQL Testcontainers, run:

```sh
./mvnw --batch-mode --no-transfer-progress -pl finbridge-host -am test
```

The lifecycle unit and PostgreSQL tests show future binding revision changes, rollback restoration, and retention of an owner with unresolved payment work. The root `clean verify` has passed these tests. The live admin HTTP E2E now changes alpha's `DISBURSE` binding from AlphaPay v1 to v2 and rolls it back to v1 using the returned persisted revisions. This is a binding-upgrade/rollback walkthrough; it does not simulate an in-flight unresolved transaction during the version change.

## Verification Status

Verified 2026-10-29 with `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export PATH="$JAVA_HOME/bin:$PATH"; ./mvnw --batch-mode --no-transfer-progress -pl finbridge-host -am test`: BUILD SUCCESS, including 37 lifecycle tests and 7 PostgreSQL control-plane integration tests. The live walkthrough ran against a fresh synthetic Compose stack with `cd frontend && FINBRIDGE_DEMO_ALLOW_INSECURE_TLS=true npm run test:e2e`; observed pass: `Provider binding rollback E2E passed: AlphaPay v1 -> v2 -> v1`. In-flight owner retention remains covered by `PluginLifecycleManagerTest` and PostgreSQL tests; the live E2E does not manufacture an ambiguous effect. The HTTP command runs the complete demo suite, not only this scenario.
