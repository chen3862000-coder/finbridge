# ADR-001: Java SPI Versus Plugin Frameworks

- **Status:** Proposed, awaiting M0 approval
- **Date:** 2026-10-26

## Context

FinBridge needs discovery of separately packaged payment and risk providers, but also needs explicit validation, activation, tenant routing, invocation tracking, draining, rollback, and resource cleanup. Java `ServiceLoader` supplies discovery only. The MVP is intended to teach these boundaries without implementing an OSGi-scale framework.

## Decision

Use Java SPI (`ServiceLoader`) for provider discovery and implement the small lifecycle/registry/lease layer required by FinBridge. Do not use PF4J or OSGi for the MVP.

## Alternatives

- PF4J: provides a plugin abstraction and lifecycle conveniences but would hide some of the class-loader/runtime mechanics that this portfolio project must demonstrate.
- OSGi: provides a much broader module/service/version ecosystem than the single-host demo needs.
- Manual class-name configuration: weaker standard discovery and more custom configuration; not selected.

## Rationale

The project explicitly treats discovery and runtime management as separate concerns. A small owned runtime makes that distinction testable and keeps the class-loading behavior visible. SPI is part of the JDK and does not introduce a plugin framework runtime dependency.

## Consequences

- FinBridge owns validation, identity, lifecycle, concurrency, draining, and cleanup correctness.
- Service descriptors and actual external JARs become integration-test fixtures.
- The design may later replace or wrap discovery if requirements outgrow the MVP.

## Risks

- A custom runtime can miss edge cases handled by mature frameworks.
- SPI discovery does not isolate dependencies or sandbox code by itself.

## Revisit Conditions

Revisit if the platform needs framework features that materially exceed the small lifecycle model, multiple extension ecosystems, or a production plugin governance/control plane.
