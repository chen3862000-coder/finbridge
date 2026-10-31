# Class-Loader Isolation

FinBridge creates a closeable class loader for each provider artifact version. Java platform types and `io.github.finbridge.spi.*` are parent-first; other classes, including provider implementation and private dependencies, are attempted child-first with parent fallback.

This preserves a single shared SPI `Class` identity while allowing two provider versions to package different versions of the same private helper class. The AlphaPay v1/v2 real-JAR integration test verifies that provider-private helper classes and defining loaders differ while the SPI interface is shared.

On unload, the runtime stops new business routing, waits for invocation leases, invokes provider close, and closes the loader when safe. JVM garbage collection and Metaspace reclamation are nondeterministic and are not guaranteed.

**Security boundary:** a class loader isolates dependency identity only. Provider code runs in-process with host privileges and can access process resources. Load trusted artifacts only; untrusted plugins require process isolation, which is not implemented. See [Plugin Lifecycle](06-plugin-lifecycle.md).
