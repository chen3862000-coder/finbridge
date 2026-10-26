# ADR-003: Controlled ClassLoader Strategy

- **Status:** Proposed, awaiting M0 approval
- **Date:** 2026-10-26

## Context

Provider versions may use incompatible private dependencies, but must implement one shared SPI. Java class identity includes the defining class loader, so loading duplicate SPI types in plugin loaders breaks assignability.

## Decision

Use one closeable URL-based class loader per plugin artifact version. Delegate Java/platform, SPI, and protected host API namespaces parent-first; load plugin implementation and approved private dependency classes child-first. Reject artifacts that bundle protected classes. Exercise the rule with real conflicting dependency JARs.

## Alternatives

- Parent-first URLClassLoader: simple, but private dependency conflicts resolve from the host/parent and are not isolated.
- Unconditional child-first: risks duplicate SPI/API classes and type identity failures.
- PF4J/OSGi: deferred under ADR-001.
- Process isolation: safer for untrusted plugins but no longer demonstrates in-JVM class identity and requires IPC.

## Rationale

Selective delegation preserves shared contract identity while allowing private dependency versions to coexist. This is the minimum policy that demonstrates the requested behavior without claiming a sandbox.

## Consequences

- Protected package policy and class-loader tests are mandatory.
- Cleanup must address TCCL, threads, executors, registries, and loader close.
- GC/Metaspace release remains nondeterministic.
- Plugins are trusted in-process code.

## Risks

- Linkage and resource leaks are difficult to diagnose.
- Plugin code can escape dependency isolation through process-level APIs.

## Revisit Conditions

Revisit if package policy becomes too complex, runtime linkage is unreliable, or plugins are not trusted; untrusted plugins require process/container isolation rather than merely a different delegation strategy.
