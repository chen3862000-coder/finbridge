# Plugin Lifecycle Operations

## Control Plane

The host plugin facades require administrator authority. Installation accepts a prebuilt filename inside the configured approved plugin directory; it does not accept an arbitrary path or upload. Persisted artifact identity includes the checksum and immutable plugin/version identity. Activation is explicit. Tenant capability bindings use PostgreSQL optimistic revisions.

Lifecycle operations affect future routing. Existing payment transactions retain their original provider/version owner. Drain and unload are refused while a binding, invocation lease, or unresolved payment owner remains. An uncertain payment must be reconciled with its recorded owner before that artifact can be removed.

## Upgrade and Rollback

Use the admin Console/API against a runnable host with a trusted admin principal. An upgrade installs and validates a candidate, activates it, commits a future binding revision, publishes that revision, and drains the old artifact only after it is no longer referenced. Rollback restores a previously registered artifact and publishes a new revision; it does not rewrite historical payment ownership.

The runnable local demo bootstrap is a fixture installer, not an operator control-plane replacement. It defaults off in `application.yml` and is enabled by the supplied Compose configuration. `DemoPluginBootstrap` contains the exact default artifact/capability fixture list. It installs available fixture JARs from `/app/plugins`, activates them, and creates a binding only when the tenant does not already have that capability bound. Existing routes are not overwritten. Other JARs packaged in the image are available for admin lifecycle demonstrations but are not automatically bound. The [Borrower Demo Usage guide](borrower-demo-usage.md#demo-tenant-capability-bindings) lists the mappings and explains how to inspect the effective persisted routes.

## Restart and Artifact Changes

On startup, the host restores active artifacts only if the approved file and persisted checksum/type/capability identity match, then restores bindings. Missing or changed artifacts fail closed and are audited. Do not overwrite an artifact under an existing identity. In the local synthetic demo, if an image rebuild changes plugin bytes, intentionally recreate the local synthetic-only database volume with `docker compose down -v` before starting the new image. This discards local demo data and must not be used against data that should be preserved.

## Evidence and Limitations

- PostgreSQL lifecycle evidence: `PluginControlPlaneIntegrationTest`.
- Host lifecycle/retention evidence: `PluginLifecycleManagerTest`.
- Startup and payment recovery evidence: `PluginRecoveryRunnerTest` and `JdbcLendingCoreIntegrationTest`.
- Decisions: ADR-005, ADR-006, ADR-007, ADR-008.

Runtime state and startup recovery are single-host. There is no distributed fencing, signed artifact verification, arbitrary upload, or sandbox. These operations are not a production deployment procedure.
