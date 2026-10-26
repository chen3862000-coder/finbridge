# ADR-004: Typed SPI Contracts

- **Status:** Proposed, awaiting M0 approval
- **Date:** 2026-10-26

## Context

Payment and risk operations have different inputs, outcomes, idempotency semantics, and capabilities. A generic operation name plus `Map<String,Object>` would move errors to runtime and obscure business guarantees.

## Decision

Define typed `PaymentProvider` and `RiskDataProvider` interfaces, shared immutable DTOs, normalized result/error categories, capability metadata, and explicit SPI API major compatibility. Keep Spring and host persistence types out of the SPI.

## Alternatives

- Generic `execute(Map<String,Object>)`: flexible shape but weak compile-time validation and difficult evolution.
- Separate bespoke host contract for every provider: prevents independent implementation against one stable API.
- Framework-specific extension API: introduces framework coupling into independently distributed providers.

## Rationale

Typed methods make business operations and normalized outcomes auditable and testable. A small explicit contract aligns with the two gateway domains and can evolve under a deliberate compatibility policy.

## Consequences

- SPI compatibility has to be tested at binary and behavior level; semver strings alone are insufficient.
- Breaking signature or semantic changes require a new SPI major.
- Provider implementations compile only against the published SPI artifact.

## Risks

- Overly broad DTOs may become accidental public API; contract changes require discipline.
- Additive default methods are not automatically safe for semantic compatibility.

## Revisit Conditions

Revisit contract boundaries if new gateway domains or asynchronous streaming operations are introduced, or if maintaining compatible versions becomes a material constraint.
