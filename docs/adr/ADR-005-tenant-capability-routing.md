# ADR-005: Tenant Capability Routing

- **Status:** Proposed, awaiting M0 approval
- **Date:** 2026-10-26

## Context

Tenants may select different provider versions for payment operations and risk-data capabilities. Provider type alone does not express that a plugin implements every operation, and client-supplied tenant IDs are not trusted identity.

## Decision

Resolve a provider using authenticated tenant identity, gateway type, required capability, and immutable plugin ID/version. Persist one binding per tenant/gateway/capability with optimistic revision. Publish immutable in-memory snapshots after database commit in the single-host MVP.

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

## Revisit Conditions

Revisit if more than one host serves traffic or tenant routing requires distributed propagation and coordinated lifecycle management.
