# Multi-Tenant Routing

## Tenant Identity

The host boundary accepts a trusted `FinBridgePrincipal` supplied by the containing Spring Security context. `HostAuthorization` derives tenant scope from that principal and requires administrator authority for plugin control operations. The authenticated local demo maps the validated OIDC issuer/subject pair to an enabled server-owned membership and constructs the principal; request bodies and headers do not choose a tenant.

The tenant capability binding is persisted with an optimistic revision. `PluginRuntime` publishes immutable binding snapshots for low-contention routing. A risk or new payment call resolves the selected provider from the requesting tenant's binding. A payment transaction persists its selected provider key, and later status/reconciliation remains pinned to that original owner even if the tenant's future route changes.

## Limits

The binding snapshot, plugin handles, recovery runner, and Compose demo are single-process/single-host. PostgreSQL arbitrates durable revisions, but the project does not claim multi-host binding publication or distributed fencing. Class-loader isolation is not tenant isolation for untrusted code; providers are trusted in-process plugins.

## Evidence

- `finbridge-host/src/main/java/io/github/finbridge/host/FinBridgePrincipal.java`
- `finbridge-host/src/main/java/io/github/finbridge/host/HostAuthorization.java`
- `finbridge-host/src/main/java/io/github/finbridge/host/TenantLendingFacade.java`
- `finbridge-host/src/main/java/io/github/finbridge/host/PluginControlPlaneRepository.java`
- `finbridge-plugin-runtime/src/main/java/io/github/finbridge/runtime/PluginBindingRegistry.java`
- `finbridge-plugin-runtime/src/main/java/io/github/finbridge/runtime/PluginRuntime.java`
- `finbridge-host/src/test/java/io/github/finbridge/host/HostAuthorizationTest.java`
- `finbridge-host/src/test/java/io/github/finbridge/host/PluginControlPlaneIntegrationTest.java`
- `finbridge-plugin-runtime/src/test/java/io/github/finbridge/runtime/PluginRuntimeIntegrationTest.java`
- ADR-005, ADR-007

The recorded root Java gate includes PostgreSQL binding-revision tests and principal/role authorization tests. ADR-014 and `DemoSecurityHttpTest` add the local authenticated HTTP boundary; they do not add production identity federation.
