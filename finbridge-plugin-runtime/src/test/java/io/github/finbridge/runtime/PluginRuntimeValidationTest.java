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
        assertThrows(IllegalStateException.class, () -> handle.activate());
        assertEquals(new PluginKey("test", "1"), handle.key());
        assertThrows(IllegalArgumentException.class,
                () -> handle.awaitDrained(Duration.ofMillis(-1)));
        handle.unload();
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
        return new PluginRuntime(pluginDirectory, getClass().getClassLoader(), 1);
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
}
