# Demo 6: Class-Loader Isolation

## Goal

Show two real provider JARs can load conflicting helper versions while sharing the parent-loaded SPI types.

## Evidence

- Loader: `PluginClassLoader`
- Artifacts: `plugins/alphapay-v1`, `plugins/alphapay-v2`, private helper modules v1/v2
- Test: `PluginRuntimeIntegrationTest.loadsRealExternalPluginJarsWithSharedSpiAndPrivateDependencyIsolation`
- Packaging boundary: `ArchitectureBoundaryTest`, `HostPackagingTest`
- Related design: `docs/architecture/03-classloader-isolation.md`

## Verification Command and Expected Result

Under JDK 21, run:

```sh
./mvnw --batch-mode --no-transfer-progress -pl integration-tests -am test
```

`ArchitectureBoundaryTest` should preserve dependency boundaries and `HostPackagingTest` should find no concrete provider/private-helper classes in the host artifact. Runtime external-JAR tests verify distinct private helper loaders and shared SPI identity. The root `clean verify` has passed the integration-tests module (3 tests). The M1 architecture doc records the original real-JAR evidence; a new screenshot was not captured.

## Verification Status

The integration test loads and invokes real assembled JARs and checks helper defining loaders and shared SPI identity. A developer-facing walkthrough with direct commands and captured output is **not yet recorded**. Isolation is not a sandbox.
