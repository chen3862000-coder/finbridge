# Plugin Lifecycle: M1

M1 implements the in-memory lifecycle states `DISCOVERED`, `VALIDATED`, `LOADED`, `ACTIVE`, `DRAINING`, `UNLOADED`, and `FAILED`. Installing validates a simple filename beneath a configured artifact root, regular/readable file and 32 MiB limit, JAR structure, SHA-256, protected SPI package exclusion, service discovery, descriptor type/API/capability, provider initialization, and health check. Successful installation registers a `LOADED` handle; activation is explicit.

An invocation lease is acquired against a specific handle. `ACTIVE` admits business and reconciliation calls. `DRAINING` rejects new business calls and permits owner reconciliation. A tenant capability binding update uses an immutable CAS snapshot and optimistic expected version. Drain/unload refuse to proceed while the plugin remains bound or a supplied `PluginRetentionPolicy` reports retained work. Unload also waits for all active leases and closes provider/loader resources.

Evidence: `PluginRuntimeValidationTest` tests path/archive/identity and state validation; `PluginRuntimeIntegrationTest` exercises actual external JARs, activation, routing switch with pinned lease, binding conflicts, drain, reconciliation eligibility, retention, timeout/unload, and close failure.

M1 lifecycle state and tenant bindings are process memory only. Startup recovery, durable audit, plugin signing, authenticated admin operations, payment transaction retention integration, and multi-host coordination are future milestone work. A database row must not be treated as evidence of an active class loader.

The provider implementation package is parent-first so Java SPI service discovery can resolve implementation class names named by the service descriptor. Other plugin-private classes are child-first, with parent fallback. The integration harness supplies a provider-free parent class loader and filters provider service resources; host packaging tests verify that the built host JAR contains neither concrete provider classes nor provider service descriptors.
