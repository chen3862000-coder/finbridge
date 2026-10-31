# Demo 6: Class-Loader Isolation

## Goal

Show two real provider JARs can load conflicting helper versions while sharing the parent-loaded SPI types.

## Evidence

- Loader: `PluginClassLoader`
- Artifacts: `plugins/alphapay-v1`, `plugins/alphapay-v2`, private helper modules v1/v2
- Test: `PluginRuntimeIntegrationTest.loadsRealExternalPluginJarsWithSharedSpiAndPrivateDependencyIsolation`
- Packaging boundary: `ArchitectureBoundaryTest`, `HostPackagingTest`
- Related design: `docs/architecture/05-classloader-isolation.md`

## Verification Command and Expected Result

Under JDK 21, run:

```sh
./mvnw --batch-mode --no-transfer-progress -pl integration-tests -am test
```

`ArchitectureBoundaryTest` preserves dependency boundaries and `HostPackagingTest` finds no concrete provider/private-helper classes in the host artifact. `PluginRuntimeIntegrationTest.loadsRealExternalPluginJarsWithSharedSpiAndPrivateDependencyIsolation` loads and invokes assembled AlphaPay v1/v2 JARs, proving separate private-helper loaders and shared SPI identity. The focused command `./mvnw --batch-mode --no-transfer-progress -pl integration-tests -am test` passed 3 integration-module tests with zero failures/errors/skips. This is machine-verifiable loader evidence; no screenshot is applicable.

## Verification Status

Verified 2026-10-29 with `export JAVA_HOME="$(/usr/libexec/java_home -v 21)"; export PATH="$JAVA_HOME/bin:$PATH"; ./mvnw --batch-mode --no-transfer-progress -pl integration-tests -am test`: BUILD SUCCESS, 3 integration-module tests passed, including the real assembled AlphaPay v1/v2 class-loader test plus architecture and host-packaging tests. `PluginRuntimeIntegrationTest` passed all 6 runtime tests. This is a test-run walkthrough rather than a live browser view. Isolation is not a sandbox.
