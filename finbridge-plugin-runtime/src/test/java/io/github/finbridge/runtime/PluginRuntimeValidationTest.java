package io.github.finbridge.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.spi.PluginDescriptor;
import io.github.finbridge.spi.PluginType;
import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.payment.PaymentProvider;
import io.github.finbridge.spi.risk.RiskDataProvider;
import io.github.finbridge.spi.risk.RiskDataResult;
import io.github.finbridge.spi.risk.RiskDataType;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginRuntimeValidationTest {
    @TempDir
    Path pluginDirectory;

    @Test
    void rejectsPathsAndNonPluginArtifactsBeforeLoading() throws IOException {
        PluginRuntime runtime = runtime();
        assertThrows(PluginLoadException.class,
                () -> runtime.install("../pom.xml", context()));
        Files.writeString(pluginDirectory.resolve("not-a-plugin.txt"), "synthetic");
        assertThrows(PluginLoadException.class,
                () -> runtime.install("not-a-plugin.txt", context()));
    }

    @Test
    void rejectsArtifactsThatBundleProtectedSpiClasses() throws Exception {
        Path jar = pluginDirectory.resolve("duplicate-spi.jar");
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new java.util.jar.JarEntry("io/github/finbridge/spi/Duplicate.class"));
            output.write(new byte[]{1});
            output.closeEntry();
        }
        assertThrows(PluginLoadException.class, () -> runtime().install(jar.getFileName().toString(),
                context()));
    }

    @Test
    void loaderCreationFailureIsReportedWithoutLeavingAnInstalledPlugin() throws Exception {
        Files.writeString(pluginDirectory.resolve("not-a-directory"), "file");
        PluginRuntime invalidRuntime = new PluginRuntime(pluginDirectory.resolve("not-a-directory"),
                getClass().getClassLoader(), 1);
        assertThrows(PluginLoadException.class,
                () -> invalidRuntime.install("candidate.jar", context()));
        assertThrows(PluginLoadException.class, () -> new PluginDiscovery(null)
                .createLoader(pluginDirectory.resolve("missing.jar")));
    }

    @Test
    void pluginKeyIdentityAndLifecycleStateAreExplicit() throws Exception {
        Path jar = pluginDirectory.resolve("fake.jar");
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new java.util.jar.JarEntry("META-INF/services/invalid.Service"));
            output.write("not.valid.Provider".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        PluginRuntime runtime = runtime();
        assertThrows(PluginLoadException.class, () -> runtime.install("fake.jar", context()));
        assertEquals(List.of(), runtime.plugins());
    }

    @Test
    void retentionPolicyIsEvaluatedForEachBindingAndKeepsPluginPinned() throws Exception {
        PluginKey key = new PluginKey("retained", "1");
        AtomicInteger checks = new AtomicInteger();
        PluginRetentionPolicy retention = ignored -> checks.incrementAndGet() > 1;
        assertFalse(retention.canUnload(key));
        assertTrue(retention.canUnload(key));
        assertEquals(2, checks.get());
    }

    @Test
    void bindingSnapshotCanBeRestoredPublishedAndRemovedByPersistedRevision() {
        PluginBindingRegistry bindings = new PluginBindingRegistry();
        UUID tenant = UUID.randomUUID();
        Capability capability = Capability.DISBURSE;
        PluginKey original = new PluginKey("restored", "1");
        PluginKey replacement = new PluginKey("restored", "2");

        assertEquals(7, bindings.restore(tenant, capability, original, 7).version());
        assertEquals(original, bindings.binding(tenant, capability).pluginKey());
        bindings.publish(new PluginBinding(tenant, capability, replacement, 8));
        assertEquals(replacement, bindings.binding(tenant, capability).pluginKey());
        assertEquals(9, bindings.publishUnbind(tenant, capability, 9));
        assertThrows(IllegalArgumentException.class, () -> bindings.binding(tenant, capability));
        assertThrows(IllegalStateException.class,
                () -> bindings.publishUnbind(tenant, capability, 10));
    }

    @Test
    void runtimeBindingRestorePublishAndRemovalRequireRealActivePluginAndMonotonicVersion()
            throws Exception {
        String artifact = "alphapay-v1-0.1.0-SNAPSHOT-jar-with-dependencies.jar";
        Path source = Path.of(System.getProperty("finbridge.root"), "plugins", "alphapay-v1",
                "target", artifact);
        Files.copy(source, pluginDirectory.resolve(artifact));
        PluginRuntime runtime = runtime();
        PluginHandle handle = runtime.install(artifact, context());
        assertThrows(IllegalStateException.class, () -> runtime.restoreBinding(UUID.randomUUID(),
                Capability.DISBURSE, handle.key(), 1));
        assertThrows(IllegalStateException.class, () -> runtime.restoreBinding(UUID.randomUUID(),
                Capability.DISBURSE, handle.key(), 0));
        assertThrows(IllegalStateException.class, () -> runtime.publishBinding(
                new PluginBinding(UUID.randomUUID(), Capability.DISBURSE, handle.key(), 1)));
        assertThrows(IllegalStateException.class, () -> runtime.publishBinding(
                new PluginBinding(UUID.randomUUID(), Capability.CREDIT_SCORE, handle.key(), 1)));
        runtime.activate(handle.key());
        UUID tenant = UUID.randomUUID();
        assertEquals(1, runtime.restoreBinding(tenant, Capability.DISBURSE, handle.key(), 1).version());
        assertEquals(handle.key(), runtime.publishBinding(
                new PluginBinding(tenant, Capability.DISBURSE, handle.key(), 2)).pluginKey());
        assertEquals(handle.key(), runtime.binding(tenant, Capability.DISBURSE).pluginKey());
        assertThrows(IllegalStateException.class, () -> runtime.restoreBinding(
                tenant, Capability.DISBURSE, handle.key(), 2));
        assertThrows(IllegalStateException.class, () -> runtime.publishBinding(
                new PluginBinding(tenant, Capability.DISBURSE, handle.key(), 1)));
        runtime.bind(tenant, Capability.DISBURSE, handle.key(), 2);
        assertEquals(4, runtime.publishUnbind(tenant, Capability.DISBURSE, 4));
        assertThrows(IllegalArgumentException.class, () -> runtime.binding(tenant, Capability.DISBURSE));
        assertThrows(IllegalStateException.class,
                () -> runtime.publishUnbind(tenant, Capability.DISBURSE, 5));
        assertThrows(IllegalStateException.class,
                () -> runtime.publishUnbind(UUID.randomUUID(), Capability.DISBURSE, 1));
        assertTrue(runtime.drain(handle.key(), Duration.ZERO, ignored -> true));
        runtime.unload(handle.key(), Duration.ZERO, ignored -> true);
        assertTrue(runtime.plugins().isEmpty());
    }

    @Test
    void runtimeDiscardsLoadedPluginAndReportsLifecycleState() throws Exception {
        String artifact = "alphapay-v1-0.1.0-SNAPSHOT-jar-with-dependencies.jar";
        Path source = Path.of(System.getProperty("finbridge.root"), "plugins", "alphapay-v1",
                "target", artifact);
        Files.copy(source, pluginDirectory.resolve(artifact));
        PluginRuntime runtime = runtime();
        PluginHandle handle = runtime.install(artifact, context());

        runtime.discardLoaded(handle.key());

        assertEquals(PluginLifecycleState.UNLOADED, handle.state());
        assertTrue(runtime.plugins().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> runtime.plugin(handle.key()));
    }

    @Test
    void operationMetricsOnlyExposeBoundedOperationAndOutcomeTags() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        PluginOperationMetrics metrics = new PluginOperationMetrics(registry);

        metrics.record(PluginOperationMetrics.Operation.PAYMENT_RECONCILE,
                PluginOperationMetrics.Outcome.UNKNOWN, Duration.ofMillis(12));

        var counter = registry.find("finbridge.plugin.operations").counter();
        var timer = registry.find("finbridge.plugin.operation.duration").timer();
        assertTrue(counter != null);
        assertTrue(timer != null);
        assertEquals(1.0, counter.count());
        assertEquals(12_000_000L, timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS));
        assertEquals(2, counter.getId().getTags().size());
        assertEquals("payment_reconcile", counter.getId().getTag("operation"));
        assertEquals("unknown", counter.getId().getTag("outcome"));
    }

    @Test
    void resumeFromDrainRestoresOnlyAnIdleDrainingHandle() throws Exception {
        PluginHandle handle = testHandle();
        handle.activate();
        handle.beginDrain();
        handle.resumeFromDrain();
        assertEquals(PluginLifecycleState.ACTIVE, handle.state());
        handle.beginDrain();
        try (PluginInvocationLease ignored = handle.acquire(InvocationKind.RECONCILIATION)) {
            assertThrows(IllegalStateException.class, handle::resumeFromDrain);
        }
        assertTrue(handle.awaitDrained(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> handle.awaitDrained(Duration.ofNanos(-1)));
        handle.unload();
    }

    @Test
    void runtimeCanResumeAnIdleDrainingPluginHandle() throws Exception {
        String artifact = "alphapay-v1-0.1.0-SNAPSHOT-jar-with-dependencies.jar";
        Path source = Path.of(System.getProperty("finbridge.root"), "plugins", "alphapay-v1",
                "target", artifact);
        Files.copy(source, pluginDirectory.resolve(artifact));
        PluginRuntime runtime = runtime();
        PluginHandle handle = runtime.install(artifact, context());
        runtime.activate(handle.key());
        handle.beginDrain();

        runtime.resumeFromDrain(handle.key());

        assertEquals(PluginLifecycleState.ACTIVE, handle.state());
        runtime.drain(handle.key(), Duration.ZERO, ignored -> true);
        runtime.unload(handle.key(), Duration.ZERO, ignored -> true);
    }

    @Test
    void registrySortsByStablePluginIdentityAndRejectsUnknownKeys() throws Exception {
        PluginRegistry registry = new PluginRegistry();
        PluginKey missing = new PluginKey("missing", "1");
        assertThrows(IllegalArgumentException.class, () -> registry.plugin(missing));
        assertTrue(registry.plugins().isEmpty());
        assertFalse(registry.remove(missing, null));
        PluginHandle handle = testHandle();
        PluginHandle duplicate = testHandle();
        PluginHandle second = testHandle(new PluginKey("registry-second", "1"));
        assertTrue(registry.putIfAbsent(handle) == null);
        assertEquals(handle, registry.putIfAbsent(duplicate));
        assertTrue(registry.putIfAbsent(second) == null);
        assertEquals(List.of(handle, second), registry.plugins());
        assertFalse(registry.remove(handle.key(), duplicate));
        assertTrue(registry.remove(handle.key(), handle));
        assertEquals(List.of(second), registry.plugins());
    }

    @Test
    void handleRejectsIllegalStateTransitionsAndLeaseDoubleCloseIsSafe() throws Exception {
        Path providerJar = pluginDirectory.resolve("empty.jar");
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(providerJar))) {
            output.putNextEntry(new java.util.jar.JarEntry("META-INF/"));
            output.closeEntry();
        }
        URLClassLoader loader = new URLClassLoader(new URL[]{providerJar.toUri().toURL()},
                getClass().getClassLoader());
        PaymentProvider provider = new PaymentProvider() {
            private final PluginDescriptor pluginDescriptor =
                    new PluginDescriptor("test", "test", "1", "1.0", PluginType.PAYMENT,
                            Set.of(Capability.DISBURSE), "Test", "test");

            @Override
            public PluginDescriptor descriptor() {
                return pluginDescriptor;
            }

            @Override
            public io.github.finbridge.spi.payment.PaymentResult disburse(
                    io.github.finbridge.spi.payment.DisbursementRequest request,
                    io.github.finbridge.spi.OperationContext context) {
                return io.github.finbridge.spi.payment.PaymentResult.succeeded("test");
            }

            @Override
            public io.github.finbridge.spi.payment.PaymentResult collect(
                    io.github.finbridge.spi.payment.CollectionRequest request,
                    io.github.finbridge.spi.OperationContext context) {
                return io.github.finbridge.spi.payment.PaymentResult.succeeded("test");
            }

            @Override
            public io.github.finbridge.spi.payment.PaymentResult queryStatus(
                    io.github.finbridge.spi.payment.PaymentTransactionRef transaction,
                    io.github.finbridge.spi.OperationContext context) {
                return new io.github.finbridge.spi.payment.PaymentResult(
                        io.github.finbridge.spi.payment.PaymentStatus.UNKNOWN, null, null, null);
            }
        };
        PluginHandle handle = new PluginHandle(provider.descriptor(), "checksum", provider, loader);
        assertThrows(IllegalStateException.class,
                () -> handle.acquire(InvocationKind.BUSINESS));
        handle.activate();
        PluginInvocationLease lease = handle.acquire(InvocationKind.BUSINESS);
        lease.close();
        lease.close();
        handle.beginDrain();
        assertEquals(0, handle.activeInvocations());
        handle.resumeFromDrain();
        assertEquals(PluginLifecycleState.ACTIVE, handle.state());
        handle.beginDrain();
        assertEquals(new PluginKey("test", "1"), handle.key());
        assertThrows(IllegalArgumentException.class,
                () -> handle.awaitDrained(Duration.ofMillis(-1)));
        handle.resumeFromDrain();
        handle.beginDrain();
        handle.unload();
    }

    @Test
    void loadedPluginCanBeDiscardedWithoutActivation() throws Exception {
        PluginHandle handle = testHandle();
        String identity = handle.key().toString();
        handle.discardLoaded();
        assertEquals(PluginLifecycleState.UNLOADED, handle.state());
        assertEquals("PLUGIN_LOAD_DISCARDED", handle.lifecycleEvents().getLast().diagnosticCode());
        assertTrue(identity.startsWith("PluginKey"));
        assertThrows(IllegalStateException.class, handle::discardLoaded);
    }

    @Test
    void discoveryValidatesRiskTypeCapabilityAndCoversAllCapabilityMappings() {
        PluginDiscovery discovery = new PluginDiscovery(getClass().getClassLoader());
        for (RiskDataType type : RiskDataType.values()) {
            Capability capability = discovery.capabilityFor(type);
            RiskFixture provider = new RiskFixture(type, capability);
            discovery.validateProviderContract(provider, provider.descriptor());
        }
        assertEquals(Capability.CREDIT_SCORE,
                discovery.capabilityFor(RiskDataType.CREDIT_SCORE));
        assertEquals(Capability.BLACKLIST,
                discovery.capabilityFor(RiskDataType.BLACKLIST));
        assertEquals(Capability.FRAUD_SIGNAL,
                discovery.capabilityFor(RiskDataType.FRAUD_SIGNAL));
        RiskFixture mismatch = new RiskFixture(RiskDataType.BLACKLIST, Capability.CREDIT_SCORE);
        assertThrows(PluginLoadException.class,
                () -> discovery.validateProviderContract(mismatch, mismatch.descriptor()));
        PluginDescriptor wrongType = new PluginDescriptor("risk", "risk", "1", "1.0",
                PluginType.PAYMENT, Set.of(Capability.CREDIT_SCORE), "Risk", "Test");
        assertThrows(PluginLoadException.class,
                () -> discovery.validateProviderContract(
                        new RiskFixture(RiskDataType.CREDIT_SCORE, Capability.CREDIT_SCORE), wrongType));
    }

    @Test
    void discoveryRejectsUnsupportedSpiMajorAndMismatchedCapabilities() {
        PluginDiscovery discovery = new PluginDiscovery(getClass().getClassLoader());
        PluginDescriptor descriptor = new PluginDescriptor("pay", "provider", "1", "2.0",
                PluginType.PAYMENT, Set.of(Capability.DISBURSE), "Payment", "Fixture");
        assertThrows(PluginLoadException.class, () -> discovery.validateDescriptor(descriptor, 1));
        PluginDescriptor crossGateway = new PluginDescriptor("pay", "provider", "1", "1.0",
                PluginType.PAYMENT, Set.of(Capability.DISBURSE, Capability.CREDIT_SCORE),
                "Payment", "Fixture");
        assertThrows(PluginLoadException.class,
                () -> discovery.validateDescriptor(crossGateway, 1));
    }

    private PluginRuntime runtime() {
        return new PluginRuntime(pluginDirectory, new ProviderFreeParent(getClass().getClassLoader()), 1);
    }

    private static io.github.finbridge.spi.PluginContext context() {
        return new io.github.finbridge.spi.PluginContext(java.util.Map.of());
    }

    private PluginHandle testHandle() throws Exception {
        return testHandle(new PluginKey("registry", "1"));
    }

    private PluginHandle testHandle(PluginKey key) throws Exception {
        Path jar = pluginDirectory.resolve("registry-" + UUID.randomUUID() + ".jar");
        try (java.util.jar.JarOutputStream output = new java.util.jar.JarOutputStream(
                Files.newOutputStream(jar))) {
            output.putNextEntry(new java.util.jar.JarEntry("empty.txt"));
            output.write("fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        PluginDescriptor descriptor = new PluginDescriptor(key.pluginId(), "test",
                key.pluginVersion(), "1.0",
                PluginType.PAYMENT, Set.of(Capability.DISBURSE), "Registry", "Test fixture");
        return new PluginHandle(descriptor, "checksum", new TestPaymentProvider(descriptor),
                new URLClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader()));
    }

    private record TestPaymentProvider(PluginDescriptor descriptor)
            implements PaymentProvider {
        @Override
        public io.github.finbridge.spi.payment.PaymentResult disburse(
                io.github.finbridge.spi.payment.DisbursementRequest request,
                io.github.finbridge.spi.OperationContext operationContext) {
            return io.github.finbridge.spi.payment.PaymentResult.succeeded("synthetic");
        }

        @Override
        public io.github.finbridge.spi.payment.PaymentResult collect(
                io.github.finbridge.spi.payment.CollectionRequest request,
                io.github.finbridge.spi.OperationContext operationContext) {
            return io.github.finbridge.spi.payment.PaymentResult.succeeded("synthetic");
        }

        @Override
        public io.github.finbridge.spi.payment.PaymentResult queryStatus(
                io.github.finbridge.spi.payment.PaymentTransactionRef transaction,
                io.github.finbridge.spi.OperationContext operationContext) {
            return new io.github.finbridge.spi.payment.PaymentResult(
                    io.github.finbridge.spi.payment.PaymentStatus.UNKNOWN, null, null, null);
        }
    }

    private record RiskFixture(RiskDataType type, Capability capability) implements RiskDataProvider {
        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor("risk-" + type.name().toLowerCase(Locale.ROOT), "risk", "1", "1.0",
                    PluginType.RISK_DATA, Set.of(capability), "Risk fixture", "Synthetic test");
        }

        @Override
        public RiskDataType supportedType() {
            return type;
        }

        @Override
        public RiskDataResult query(
                io.github.finbridge.spi.risk.RiskDataRequest request,
                io.github.finbridge.spi.OperationContext context) {
            return new RiskDataResult(type, RiskDataResult.Status.UNAVAILABLE, null, "TEST");
        }
    }

    private static final class ProviderFreeParent extends ClassLoader {
        private static final String PROVIDER_PACKAGE = "io.github.finbridge.plugins.";
        private static final String PAYMENT_SERVICE =
                "META-INF/services/io.github.finbridge.spi.payment.PaymentProvider";
        private static final String RISK_SERVICE =
                "META-INF/services/io.github.finbridge.spi.risk.RiskDataProvider";

        private ProviderFreeParent(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(PROVIDER_PACKAGE)) {
                throw new ClassNotFoundException("Provider implementations must come from external JARs");
            }
            return super.loadClass(name, resolve);
        }

        @Override
        public URL getResource(String name) {
            return isProviderService(name) ? null : super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return isProviderService(name) ? Collections.emptyEnumeration() : super.getResources(name);
        }

        private static boolean isProviderService(String name) {
            return PAYMENT_SERVICE.equals(name) || RISK_SERVICE.equals(name);
        }
    }
}
