package io.github.finbridge.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.finbridge.spi.PluginContext;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginRuntimeLifecycleCallbackTest {
    private static final String ALPHAPAY_JAR = "alphapay-v1-0.1.0-SNAPSHOT-jar-with-dependencies.jar";

    @TempDir
    Path pluginDirectory;

    @Test
    void runtimeQuarantinesASecondTimedOutHandleForTheSameIdentity() throws Exception {
        Path artifact = copyPlugin("same-timeout.jar");
        try (PluginRuntime runtime = runtime(Duration.ofMillis(100))) {
            assertThrows(PluginLoadException.class,
                    () -> runtime.install(artifact.getFileName().toString(), context("initialize", "1000")));
            assertThrows(PluginLoadException.class,
                    () -> runtime.install(artifact.getFileName().toString(), context("initialize", "1000")));
            assertEquals(1, runtime.plugins().size());
            assertEquals(PluginLifecycleState.FAILED, runtime.plugins().getFirst().state());
        }
    }

    @Test
    void runtimeFailsWhenDuplicateTimedOutIdentityCannotBeRegisteredForQuarantine() throws Exception {
        Path artifact = copyPlugin("same-timeout-identity.jar");
        try (PluginRuntime runtime = runtime(Duration.ofMillis(120))) {
            assertThrows(PluginLoadException.class,
                    () -> runtime.install(artifact.getFileName().toString(), context("initialize", "1000")));
            assertThrows(PluginLoadException.class,
                    () -> runtime.install(artifact.getFileName().toString(), context("initialize", "1000")));
            assertEquals(1, runtime.plugins().size());
            assertEquals(PluginLifecycleState.FAILED, runtime.plugins().getFirst().state());
        }
    }

    @Test
    void runtimeDiscardsQuarantinedProviderWhenHandleRegistrationConflicts() throws Exception {
        Path first = copyPlugin("timeout-conflict.jar");
        Path second = Files.copy(first, pluginDirectory.resolve("timeout-conflict-copy.jar"));
        try (PluginRuntime runtime = runtime(Duration.ofMillis(120))) {
            assertThrows(PluginLoadException.class,
                    () -> runtime.install(first.getFileName().toString(), context("initialize", "1000")));
            assertThrows(PluginLoadException.class,
                    () -> runtime.install(second.getFileName().toString(), context("initialize", "1000")));
            assertEquals(1, runtime.plugins().size());
            assertEquals(PluginLifecycleState.FAILED, runtime.plugins().getFirst().state());
        }
    }

    @Test
    void runtimeDiscardsQuarantinedProviderIfLoaderCloseFailsOnIdentityCollision() throws Exception {
        Path first = copyPlugin("timeout-close-conflict.jar");
        Path second = Files.copy(first, pluginDirectory.resolve("timeout-close-conflict-copy.jar"));
        try (PluginRuntime runtime = runtime(Duration.ofMillis(120))) {
            assertThrows(PluginLoadException.class,
                    () -> runtime.install(first.getFileName().toString(), context("initialize", "1000")));
            assertThrows(PluginLoadException.class,
                    () -> runtime.install(second.getFileName().toString(), context("initialize", "1000")));
            assertEquals(1, runtime.plugins().size());
            assertEquals(PluginLifecycleState.FAILED, runtime.plugins().getFirst().state());
        }
    }

    @Test
    void runtimeDiscardsQuarantinedLoaderAndPreservesRuntimeOnCloseFailure() throws Exception {
        try (PluginRuntime runtime = runtime(Duration.ofSeconds(1))) {
            java.net.URLClassLoader loader = new java.net.URLClassLoader(new URL[0], getClass().getClassLoader()) {
                @Override
                public void close() throws java.io.IOException {
                    throw new java.io.IOException("synthetic loader close failure");
                }
            };
            runtime.discardQuarantinedLoader(loader);
            assertEquals(0, runtime.plugins().size());
        }
    }

    @Test
    void callbackTimeoutCanBeRegisteredAsFailedAndDuplicateIdentityFailsClosed() throws Exception {
        Path artifact = copyPlugin("timeout-duplicate.jar");
        try (PluginRuntime runtime = runtime(Duration.ofMillis(100))) {
            assertThrows(PluginLoadException.class,
                    () -> runtime.install(artifact.getFileName().toString(), context("initialize", "1000")));
            assertThrows(PluginLoadException.class,
                    () -> runtime.install(artifact.getFileName().toString(), context("initialize", "1000")));
            assertEquals(1, runtime.plugins().size());
            assertEquals(PluginLifecycleState.FAILED, runtime.plugins().getFirst().state());
        }
    }

    @Test
    void runtimeKeepsTheOriginalQuarantinedHandleWhenASecondVersionTimesOut() throws Exception {
        Path first = copyPlugin("timeout-original.jar");
        Path second = Files.copy(first, pluginDirectory.resolve("timeout-second-filename.jar"));
        try (PluginRuntime runtime = runtime(Duration.ofMillis(120))) {
            assertThrows(PluginLoadException.class,
                    () -> runtime.install(first.getFileName().toString(), context("initialize", "1000")));
            assertThrows(PluginLoadException.class,
                    () -> runtime.install(second.getFileName().toString(), context("initialize", "1000")));
            assertEquals(1, runtime.plugins().size());
            assertEquals(PluginLifecycleState.FAILED, runtime.plugins().getFirst().state());
        }
    }

    @Test
    void runtimeCanDiscardQuarantinedLoaderAfterIdentityConflict() throws Exception {
        try (PluginRuntime runtime = runtime(Duration.ofSeconds(1));
             PluginClassLoader loader = new PluginClassLoader(Path.of("plugins", ALPHAPAY_JAR).toUri().toURL(),
                     getClass().getClassLoader())) {
            runtime.discardQuarantinedLoader(loader);
            assertTrue(runtime.plugins().isEmpty());
        }
    }

    @Test
    void runtimeDiscardsASecondTimedOutProviderForSameIdentity() throws Exception {
        Path first = copyPlugin("timeout-same-identity.jar");
        Path second = Files.copy(first, pluginDirectory.resolve("timeout-same-identity-copy.jar"));
        try (PluginRuntime runtime = runtime(Duration.ofMillis(120))) {
            assertThrows(PluginLoadException.class,
                    () -> runtime.install(first.getFileName().toString(), context("initialize", "1000")));
            assertThrows(PluginLoadException.class,
                    () -> runtime.install(second.getFileName().toString(), context("initialize", "1000")));
            assertEquals(1, runtime.plugins().size());
            assertEquals(PluginLifecycleState.FAILED, runtime.plugins().getFirst().state());
        }
    }

    @Test
    void runtimeDiscardsTimedOutProviderWhenItsIdentityIsAlreadyRegistered() throws Exception {
        Path firstArtifact = copyPlugin("timeout-one.jar");
        Path secondArtifact = Files.copy(firstArtifact, pluginDirectory.resolve("timeout-two.jar"));
        try (PluginRuntime runtime = runtime(Duration.ofMillis(100))) {
            assertThrows(PluginLoadException.class,
                    () -> runtime.install(firstArtifact.getFileName().toString(), context("initialize", "1000")));
            assertThrows(PluginLoadException.class,
                    () -> runtime.install(secondArtifact.getFileName().toString(), context("initialize", "1000")));
            assertEquals(1, runtime.plugins().size());
            assertEquals(PluginLifecycleState.FAILED, runtime.plugins().getFirst().state());
        }
    }

    @Test
    void boundsRealJarInitializationHealthAndCloseCallbacks() throws Exception {
        assertTimedOut("initialize", PluginLifecycleState.FAILED);
        assertTimedOut("health", PluginLifecycleState.FAILED);
        Path artifact = copyPlugin("close-timeout.jar");
        try (PluginRuntime runtime = runtime(Duration.ofMillis(100))) {
            PluginHandle handle = runtime.install(artifact.getFileName().toString(), context("close", "1000"));
            runtime.activate(handle.key());
            handle.beginDrain();
            assertThrows(java.io.IOException.class, handle::unload);
            assertEquals(PluginLifecycleState.FAILED, handle.state());
            assertEquals("PLUGIN_CLOSE_FAILED", handle.lifecycleEvents().getLast().diagnosticCode());
        }
    }

    @Test
    void timesOutARealJarCallbackAndQuarantinesItsHandle() throws Exception {
        Path artifact = copyPlugin("initialize-timeout.jar");
        try (PluginRuntime runtime = runtime(Duration.ofMillis(100))) {
            long started = System.nanoTime();
            PluginLoadException failure = assertThrows(PluginLoadException.class,
                    () -> runtime.install(artifact.getFileName().toString(), context("initialize", "1000")));
            assertTrue(hasTimeoutCause(failure), causeChain(failure));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0);
            assertEquals(PluginLifecycleState.FAILED, runtime.plugins().getFirst().state());
            assertEquals("PLUGIN_LIFECYCLE_CALLBACK_TIMED_OUT",
                    runtime.plugins().getFirst().lifecycleEvents().getLast().diagnosticCode());
        }
    }

    private void assertTimedOut(String callback, PluginLifecycleState expected) throws Exception {
        Path artifact = copyPlugin(callback + "-timeout.jar");
        try (PluginRuntime runtime = runtime(Duration.ofMillis(100))) {
            PluginLoadException failure = assertThrows(PluginLoadException.class,
                    () -> runtime.install(artifact.getFileName().toString(), context(callback, "1000")));
            assertTrue(hasTimeoutCause(failure), causeChain(failure));
            assertEquals(expected, runtime.plugins().getFirst().state());
        }
    }

    private Path copyPlugin(String name) throws Exception {
        Path source = Path.of(System.getProperty("finbridge.root"), "plugins", "alphapay-v1",
                "target", ALPHAPAY_JAR);
        return Files.copy(source, pluginDirectory.resolve(name));
    }

    private PluginRuntime runtime(Duration timeout) {
        return new PluginRuntime(pluginDirectory, new ProviderFreeParent(getClass().getClassLoader()), 1, timeout);
    }

    private static PluginContext context(String callback, String delay) {
        return new PluginContext(Map.of("test.lifecycle." + callback + "-delay-ms", delay));
    }

    private static boolean hasTimeoutCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PluginLifecycleCallbackException callbackFailure
                    && callbackFailure.timedOut()) {
                return true;
            }
        }
        return false;
    }

    private static String causeChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            chain.append(cause.getClass().getName()).append(": ").append(cause.getMessage()).append('\n');
        }
        return chain.toString();
    }

    private static final class ProviderFreeParent extends ClassLoader {
        private ProviderFreeParent(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("io.github.finbridge.plugins.")) {
                throw new ClassNotFoundException("Provider classes must come from the external artifact");
            }
            return super.loadClass(name, resolve);
        }

        @Override
        public URL getResource(String name) {
            return isProviderService(name) ? null : super.getResource(name);
        }

        @Override
        public java.util.Enumeration<URL> getResources(String name) throws java.io.IOException {
            return isProviderService(name) ? java.util.Collections.emptyEnumeration() : super.getResources(name);
        }

        private static boolean isProviderService(String name) {
            return name.startsWith("META-INF/services/io.github.finbridge.spi.");
        }
    }
}
