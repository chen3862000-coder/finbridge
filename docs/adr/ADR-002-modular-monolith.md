# ADR-002: Modular Monolith

- **Status:** Proposed, awaiting M0 approval
- **Date:** 2026-10-26

## Context

The demonstration needs clear business, gateway, runtime, and host boundaries, but its core proof is in-process class loading and version pinning. Splitting the host into network services would add deployment and consistency complexity without strengthening that proof.

## Decision

Build one Spring Boot host as a Maven modular monolith. Build provider implementations as independent JAR artifacts with no host runtime dependency on them. Keep the SPI framework-neutral.

## Alternatives

- Microservices for payment, risk, plugin management, and administration: stronger process isolation but requires remote protocols and distributed coordination, diluting the JVM class-loader demonstration.
- One flat application module: less build structure but weakens dependency-boundary evidence.

## Rationale

The modular monolith provides a small deployment while compile-time module boundaries and packaging checks demonstrate inversion of dependency. Provider JARs remain independently distributable and loadable.

## Consequences

- The host is a single failure and scaling domain in the MVP.
- Maven reactor membership does not imply runtime dependency; packaging tests must prove provider artifacts are absent from the host.
- Future network separation remains possible at gateway boundaries.

## Risks

- Maven modules can still form an overly coupled graph if dependency rules are not enforced.
- Trusted provider code can affect the host process.

## Revisit Conditions

Revisit when independent deployment/scaling, regulatory isolation, or untrusted-provider execution is a requirement rather than a portfolio demonstration.
