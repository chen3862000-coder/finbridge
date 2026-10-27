# ADR-005: Tenant Capability Routing

- **Status:** Accepted for M5 implementation
- **Date:** 2026-10-27

## Context

Tenants may select different provider versions for payment operations and risk-data capabilities. Provider type alone does not express that a plugin implements every operation, and client-supplied tenant IDs are not trusted identity.

## Decision

Resolve a provider using authenticated tenant identity, gateway type, required capability, and immutable plugin ID/version. Persist one binding per tenant/gateway/capability with optimistic revision. Publish immutable in-memory snapshots after database commit in the single-host MVP.

For M5, the host facade derives the tenant from a Spring Security `Authentication` whose principal is a FinBridge-owned principal type; request-level tenant values are not accepted by facade methods. Tenant reads and payment recovery are scoped to that principal. Plugin lifecycle and binding control operations require the `FINBRIDGE_ADMIN` role. Bindings are persisted using compare-and-set revisions; runtime snapshots are rebuilt from active persisted bindings at startup. The host serializes single-process binding publication and records control-plane audit events. This does not claim multi-host coordination.

## Alternatives

- One provider per tenant: cannot mix risk capabilities or operation-level support.
- Client-provided provider/tenant selection: authorization and consistency risk.
- Database lookup plus global lock per request: simple but serializes ordinary calls and couples I/O to control-plane updates.
- Distributed configuration service: unnecessary for a single-host MVP.

## Rationale

Capability-level bindings match the business requirement and let the router reject unsupported operations before invocation. Server-derived identity and versioned compare-and-set updates make tenant boundaries and configuration races explicit.

## Consequences

- Binding changes affect future invocations only.
- Single-host snapshot publication is not a multi-host consistency guarantee.
- Drain can make stale bindings unavailable; operations must rebind before unload.

## Risks

- Database commit and process snapshot publication need recovery handling.
- Stale writes must return a conflict, not silently overwrite another administrator's change.
- Authentication is an in-process service boundary only; credential issuance, HTTP filters/endpoints, and external identity federation are not part of M5.

## Revisit Conditions

Revisit if more than one host serves traffic or tenant routing requires distributed propagation and coordinated lifecycle management.
