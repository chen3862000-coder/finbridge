package io.github.finbridge.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.PluginContext;
import io.github.finbridge.spi.payment.PaymentProvider;
import io.github.finbridge.spi.payment.PaymentStatus;
import io.github.finbridge.spi.payment.PaymentTransactionRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Collections;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginRuntimeIntegrationTest {
    @TempDir
    Path pluginDirectory;

    @Test
    void loadsRealExternalPluginJarsWithSharedSpiAndPrivateDependencyIsolation() throws Exception {
        copyPlugin("alphapay-v1", "alphapay-v1-0.1.0-SNAPSHOT-jar-with-dependencies.jar");
        copyPlugin("alphapay-v2", "alphapay-v2-0.1.0-SNAPSHOT-jar-with-dependencies.jar");
        PluginRuntime runtime = runtime();
        PluginHandle v1 = runtime.install("alphapay-v1.jar",
                new PluginContext(Map.of()));
        PluginHandle v2 = runtime.install("alphapay-v2.jar",
                new PluginContext(Map.of()));

        assertEquals(PluginLifecycleState.LOADED, v1.state());
        assertEquals(PluginLifecycleState.LOADED, v2.state());
        assertNotEquals(v1.sha256(), v2.sha256());
        assertNotEquals(v1.classLoaderIdentity(), v2.classLoaderIdentity());

        runtime.activate(v1.key());
        runtime.activate(v2.key());
        try (PluginInvocationLease leaseV1 = v1.acquire(InvocationKind.BUSINESS);
             PluginInvocationLease leaseV2 = v2.acquire(InvocationKind.BUSINESS)) {
            var resultV1 = leaseV1.invoke(provider -> ((PaymentProvider) provider)
                    .queryStatus(new PaymentTransactionRef("alphapay-private-v1-transaction"),
                            context()));
            var resultV2 = leaseV2.invoke(provider -> ((PaymentProvider) provider)
                    .queryStatus(new PaymentTransactionRef("alphapay-private-v2-transaction"),
                            context()));
            assertEquals(PaymentStatus.SUCCEEDED, resultV1.status());
            assertEquals(PaymentStatus.SUCCEEDED, resultV2.status());

            Class<?> helperV1 = loadPrivateHelper(leaseV1);
            Class<?> helperV2 = loadPrivateHelper(leaseV2);
            assertNotEquals(helperV1, helperV2);
            assertNotEquals(helperV1.getClassLoader(), helperV2.getClassLoader());
            ClassLoader apiLoader = leaseV1.invoke(provider ->
                    provider.getClass().getInterfaces()[0].getClassLoader());
            assertEquals(apiLoader, PaymentProvider.class.getClassLoader());
            ClassLoader implementationLoader = leaseV1.invoke(
                    provider -> provider.getClass().getClassLoader());
            assertNotEquals(apiLoader, implementationLoader);
            assertEquals(PluginLifecycleState.ACTIVE, v1.state());
            assertEquals(PluginLifecycleState.ACTIVE, v2.state());
            assertTrue(v1.sha256().matches("[a-f0-9]{64}"));
        }
    }

    @Test
    void bindingUpdatesAreOptimisticAndRoutingPinsSelectedHandle() throws Exception {
        copyPlugin("alphapay-v1", "alphapay-v1-0.1.0-SNAPSHOT-jar-with-dependencies.jar");
        copyPlugin("alphapay-v2", "alphapay-v2-0.1.0-SNAPSHOT-jar-with-dependencies.jar");
        PluginRuntime runtime = runtime();
        PluginHandle v1 = runtime.install("alphapay-v1.jar",
                new PluginContext(Map.of()));
        PluginHandle v2 = runtime.install("alphapay-v2.jar",
                new PluginContext(Map.of()));
        runtime.activate(v1.key());
        runtime.activate(v2.key());
        UUID tenantId = UUID.randomUUID();

        assertEquals(1, runtime.bind(tenantId, Capability.DISBURSE, v1.key(), 0).version());
        assertThrows(IllegalStateException.class,
                () -> runtime.bind(tenantId, Capability.DISBURSE, v2.key(), 0));
        try (PluginInvocationLease pinned = runtime.route(tenantId, Capability.DISBURSE,
                InvocationKind.BUSINESS)) {
            assertEquals(v1.key(), pinned.handle().key());
            runtime.bind(tenantId, Capability.DISBURSE, v2.key(), 1);
            assertEquals(v1.key(), pinned.handle().key());
            try (PluginInvocationLease next = runtime.route(tenantId, Capability.DISBURSE,
                    InvocationKind.BUSINESS)) {
                assertEquals(v2.key(), next.handle().key());
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> runtime.bind(tenantId, Capability.CREDIT_SCORE, v2.key(), 0));
    }

    @Test
    void drainingBlocksNewBusinessAndHonorsBindingsAndOwnerRetention() throws Exception {
        copyPlugin("alphapay-v1", "alphapay-v1-0.1.0-SNAPSHOT-jar-with-dependencies.jar");
        PluginRuntime runtime = runtime();
        PluginHandle handle = runtime.install(
                "alphapay-v1.jar", new PluginContext(Map.of()));
        runtime.activate(handle.key());
        UUID tenantId = UUID.randomUUID();
        runtime.bind(tenantId, Capability.DISBURSE, handle.key(), 0);
        assertFalse(runtime.drain(handle.key(), Duration.ZERO, ignored -> true));
        assertEquals(PluginLifecycleState.DRAINING, handle.state());
        assertThrows(IllegalStateException.class,
                () -> handle.acquire(InvocationKind.BUSINESS));
        try (PluginInvocationLease ignored = handle.acquire(InvocationKind.RECONCILIATION)) {
            assertEquals(1, handle.activeInvocations());
        }

        assertFalse(runtime.drain(handle.key(), Duration.ZERO, ignored -> false));
        runtime.unbind(tenantId, Capability.DISBURSE, 1);
        assertFalse(runtime.drain(handle.key(), Duration.ZERO, ignored -> false));
        assertTrue(runtime.drain(handle.key(), Duration.ZERO, ignored -> true));
        runtime.unload(handle.key(), Duration.ZERO, ignored -> true);
        assertEquals(PluginLifecycleState.UNLOADED, handle.state());
    }

    @Test
    void drainWaitsForLeasesAndUnloadClosesPluginResources() throws Exception {
        copyPlugin("alphapay-v1", "alphapay-v1-0.1.0-SNAPSHOT-jar-with-dependencies.jar");
        PluginRuntime runtime = runtime();
        PluginHandle handle = runtime.install(
                "alphapay-v1.jar", new PluginContext(Map.of()));
        runtime.activate(handle.key());
        PluginInvocationLease lease = handle.acquire(InvocationKind.BUSINESS);
        handle.beginDrain();
        assertThrows(IllegalStateException.class,
                () -> runtime.unload(handle.key(), Duration.ZERO, ignored -> false));
        assertThrows(IllegalStateException.class,
                () -> runtime.unload(handle.key(), Duration.ZERO, ignored -> true));
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Boolean> drained = new AtomicReference<>(false);
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                drained.set(handle.awaitDrained(Duration.ofSeconds(2)));
                completed.countDown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        lease.close();
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        waiter.join();
        assertTrue(drained.get());
        runtime.unload(handle.key(), Duration.ZERO, ignored -> true);
        assertTrue(runtime.plugins().isEmpty());
        assertEquals(PluginLifecycleState.UNLOADED, handle.state());
    }

    @Test
    void failedProviderCloseMarksHandleFailedAndStillClosesLoader() throws Exception {
        Path providerJar = pluginDirectory.resolve("close-failure.jar");
        try (java.util.jar.JarOutputStream output = new java.util.jar.JarOutputStream(
                Files.newOutputStream(providerJar))) {
            output.putNextEntry(new java.util.jar.JarEntry("empty.txt"));
            output.write("fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        PluginHandle handle = handleWithCloseFailure(providerJar);
        assertThrows(java.io.IOException.class, handle::unload);
        assertEquals(PluginLifecycleState.FAILED, handle.state());
        assertEquals("PLUGIN_CLOSE_FAILED",
                handle.lifecycleEvents().getLast().diagnosticCode());
    }

    @Test
    void registrationReturnsSameBytesAndRejectsChangedBytesForSameIdentity() throws Exception {
        copyPlugin("alphapay-v1", "alphapay-v1-0.1.0-SNAPSHOT-jar-with-dependencies.jar");
        PluginRuntime runtime = runtime();
        String artifactName = "alphapay-v1.jar";
        PluginHandle original = runtime.install(
                artifactName, new PluginContext(Map.of()));
        Files.copy(pluginDirectory.resolve(artifactName),
                pluginDirectory.resolve("alphapay-v1-copy.jar"));
        PluginHandle sameIdentity = runtime.install("alphapay-v1-copy.jar", new PluginContext(Map.of()));
        assertEquals(original, sameIdentity);

        Path changedBytes = pluginDirectory.resolve("alphapay-v1-changed.jar");
        try (java.util.jar.JarOutputStream output = new java.util.jar.JarOutputStream(
                Files.newOutputStream(changedBytes))) {
            output.putNextEntry(new java.util.jar.JarEntry("META-INF/services/invalid.Service"));
            output.write("invalid.Provider".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        assertThrows(PluginLoadException.class,
                () -> runtime.install("alphapay-v1-changed.jar", new PluginContext(Map.of())));
    }

    private PluginRuntime runtime() {
        return new PluginRuntime(pluginDirectory,
                new PluginFreeParentClassLoader(getClass().getClassLoader()), 1);
    }

    private void copyPlugin(String module, String jarName) throws Exception {
        Path source = Path.of(System.getProperty("finbridge.root"), "plugins", module,
                "target", jarName);
        Files.copy(source, pluginDirectory.resolve(module + ".jar"));
    }

    private static Class<?> loadPrivateHelper(PluginInvocationLease lease) {
        return lease.invoke(provider -> {
            try {
                return Class.forName("io.github.finbridge.plugins.privateapi.Helper", false,
                        provider.getClass().getClassLoader());
            } catch (ClassNotFoundException failure) {
                throw new AssertionError(failure);
            }
        });
    }

    private static OperationContext context() {
        return new OperationContext(UUID.randomUUID(), "integration-test",
                Instant.now().plusSeconds(5));
    }

    private static PluginHandle handleWithCloseFailure(Path providerJar) throws Exception {
        java.net.URLClassLoader pluginLoader = new java.net.URLClassLoader(
                new URL[]{providerJar.toUri().toURL()}, PluginRuntimeIntegrationTest.class.getClassLoader());
        PaymentProvider provider = new PaymentProvider() {
            private final io.github.finbridge.spi.PluginDescriptor pluginDescriptor =
                    new io.github.finbridge.spi.PluginDescriptor("close-failure", "test", "1", "1.0",
                            io.github.finbridge.spi.PluginType.PAYMENT,
                            java.util.Set.of(Capability.DISBURSE), "Close failure", "Test fixture");

            @Override
            public io.github.finbridge.spi.PluginDescriptor descriptor() {
                return pluginDescriptor;
            }

            @Override
            public io.github.finbridge.spi.payment.PaymentResult disburse(
                    io.github.finbridge.spi.payment.DisbursementRequest request, OperationContext context) {
                return io.github.finbridge.spi.payment.PaymentResult.succeeded("test");
            }

            @Override
            public io.github.finbridge.spi.payment.PaymentResult collect(
                    io.github.finbridge.spi.payment.CollectionRequest request, OperationContext context) {
                return io.github.finbridge.spi.payment.PaymentResult.succeeded("test");
            }

            @Override
            public io.github.finbridge.spi.payment.PaymentResult queryStatus(
                    PaymentTransactionRef transaction, OperationContext context) {
                return new io.github.finbridge.spi.payment.PaymentResult(
                        PaymentStatus.UNKNOWN, null, null, null);
            }

            @Override
            public void close() {
                throw new IllegalStateException("synthetic close failure");
            }
        };
        PluginHandle handle = new PluginHandle(provider.descriptor(), "checksum", provider, pluginLoader);
        handle.activate();
        handle.beginDrain();
        return handle;
    }

    private static final class PluginFreeParentClassLoader extends ClassLoader {
        private static final String PROVIDER_PACKAGE = "io.github.finbridge.plugins.";
        private static final String PAYMENT_SERVICE =
                "META-INF/services/io.github.finbridge.spi.payment.PaymentProvider";
        private static final String RISK_SERVICE =
                "META-INF/services/io.github.finbridge.spi.risk.RiskDataProvider";

        private PluginFreeParentClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(PROVIDER_PACKAGE)) {
                throw new ClassNotFoundException("Provider classes must only exist in external JARs");
            }
            return super.loadClass(name, resolve);
        }

        @Override
        public URL getResource(String name) {
            return isServiceResource(name) ? null : super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws java.io.IOException {
            return isServiceResource(name) ? Collections.emptyEnumeration() : super.getResources(name);
        }

        private static boolean isServiceResource(String name) {
            return PAYMENT_SERVICE.equals(name) || RISK_SERVICE.equals(name);
        }
    }
}
