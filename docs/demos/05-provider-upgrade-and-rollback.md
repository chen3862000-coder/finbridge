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

The lifecycle unit and PostgreSQL tests should show future binding revision changes, rollback restoration, and retention of an owner with unresolved payment work. The root `clean verify` has passed these tests. No local UI upgrade walkthrough has been executed.

## Verification Status

Lifecycle and persisted revision behavior are covered by tests. A repeatable Compose command walkthrough that upgrades/rolls back with in-flight ownership is **not yet authored or executed**. The local borrower HTTP script does not exercise admin lifecycle controls beyond registry read.
