# ClassLoader Isolation: M1

Each artifact load receives a closeable `PluginClassLoader`. Java platform classes and `io.github.finbridge.spi.*` are parent-first. Other classes are attempted child-first, with fallback to the parent. Thus the shared SPI interface remains the same `Class` object, while provider-private dependencies can be defined separately by each plugin loader.

The integration fixtures are compiled provider artifacts, not class-name simulations. `alphapay-v1` and `alphapay-v2` each package a helper named `io.github.finbridge.plugins.privateapi.Helper` with different behavior. Their provider implementation packages are on the test runtime so `ServiceLoader`'s service-descriptor lookup can discover them; the integration test supplies a provider-free parent loader that denies provider classes and service resources, reproducing the intended host condition. The actual helper is defined by each plugin loader.

`PluginRuntimeIntegrationTest.loadsRealExternalPluginJarsWithSharedSpiAndPrivateDependencyIsolation` asserts that both versions load and execute, their helper classes/loaders differ, and their `PaymentProvider` interface is the parent SPI class. The test also asserts the provider implementation is defined by the plugin loader.

On unload, the runtime stops new business leases, waits for outstanding leases, calls provider `close`, restores TCCL, closes the URL loader, and removes registry references. JVM garbage collection and Metaspace reclamation are nondeterministic and are not claimed. Plugin code is trusted code in the same JVM; this mechanism isolates dependency identity, not security. Untrusted plugins require process isolation.
