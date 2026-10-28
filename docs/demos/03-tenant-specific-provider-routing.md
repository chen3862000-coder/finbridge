# Demo 3: Tenant-Specific Provider Routing

## Goal

Show capability routing is selected by tenant, while core business logic remains provider-independent.

## Evidence

- Runtime: `PluginRuntime.route`, `PluginBindingRegistry`
- Durable control plane: `PluginControlPlaneRepository.bindingSwitch`
- Tests: `PluginRuntimeIntegrationTest.bindingUpdatesAreOptimisticAndRoutingPinsSelectedHandle`, `PluginControlPlaneIntegrationTest`
- Related design: ADR-005 and `docs/architecture/10-multi-tenant-routing.md`

## Verification Command and Expected Result

Under JDK 21 with Docker available for PostgreSQL Testcontainers, run:

```sh
./mvnw --batch-mode --no-transfer-progress -pl finbridge-host -am test
```

The runtime test should prove optimistic tenant binding and route pinning; the host PostgreSQL test should prove persisted revision changes and stale-revision rejection. The root `clean verify` has passed these tests. This is module-level evidence, not a separate interactive Console walkthrough.

## Verification Status

Automated runtime and PostgreSQL tests cover tenant binding revisions and routing. A separate runnable end-user walkthrough with two different provider selections is **not yet documented or live-executed**. The Compose borrower E2E currently uses one synthetic provider configuration shared across seeded tenants.
