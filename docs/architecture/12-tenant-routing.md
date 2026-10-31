# Tenant Capability Routing

The trusted host principal supplies tenant identity. Request bodies/headers cannot select a tenant. The local demo maps validated OIDC issuer/subject identities to server-owned memberships and synthetic applicant references. Borrower APIs enforce applicant ownership within the tenant; operations views may be tenant-wide; plugin administration requires the admin role.

A durable binding maps a `(tenant, capability)` pair to one active provider ID/version and optimistic revision. The control plane commits the revision before publishing an immutable in-memory routing snapshot. A new gateway call resolves the current route and acquires a lease on that provider. A payment status query is an exception: it follows the payment's persisted owner, not current routing.

Bindings are persisted in `tenant_plugin_bindings`; they can be created by the optional local demo bootstrap or an administrator. The [borrower demo guide](../guides/borrower-demo-usage.md#demo-tenant-capability-bindings) lists defaults and inspection steps. Routing publication and leases are single-host; PostgreSQL revisions do not establish multi-host coordination or distributed fencing.
