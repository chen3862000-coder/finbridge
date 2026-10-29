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

The runtime test proves optimistic tenant binding and route pinning; the host PostgreSQL test proves persisted revision changes and stale-revision rejection. The root `clean verify` passes these tests. The live two-tenant route change is also demonstrated by the authenticated E2E below.

## Verification Status

Live command from `frontend/`: `FINBRIDGE_DEMO_ALLOW_INSECURE_TLS=true npm run test:e2e`. Observed pass: `Tenant-specific routing E2E passed: beta disbursement used BetaPay`. Admin calls bind beta's `DISBURSE` capability to BetaPay using its persisted revision; beta's synthetic transaction reference starts with `betapay-transaction-`, while alpha uses AlphaPay. This proves only this local synthetic routing scenario, not multi-host routing.
