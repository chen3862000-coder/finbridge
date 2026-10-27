package io.github.finbridge.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.finbridge.runtime.PluginHandle;
import io.github.finbridge.runtime.PluginInvocationLease;
import io.github.finbridge.runtime.PluginRuntime;
import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.PluginContext;
import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.spi.PluginType;
import io.github.finbridge.spi.payment.DisbursementRequest;
import io.github.finbridge.spi.payment.PaymentStatus;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaymentGatewayIntegrationTest {
    private static final String JAR = "alphapay-v1-0.1.0-SNAPSHOT-jar-with-dependencies.jar";
    private static final String BETA_JAR = "betapay-0.1.0-SNAPSHOT-jar-with-dependencies.jar";
    @TempDir
    Path plugins;

    @Test
    void invokesAndReconcilesThroughPinnedExternalProviderAndClientKey() throws Exception {
        Files.copy(Path.of(System.getProperty("finbridge.root"), "plugins", "alphapay-v1",
                "target", JAR), plugins.resolve(JAR));
        PluginRuntime runtime = new PluginRuntime(plugins, new ProviderFreeParent(getClass().getClassLoader()), 1);
        PluginHandle provider = runtime.install(JAR, new PluginContext(Map.of()));
        assertEquals(PluginType.PAYMENT, provider.descriptor().pluginType());
        assertEquals(Set.of(Capability.COLLECT, Capability.DISBURSE, Capability.QUERY_STATUS),
                provider.descriptor().capabilities());
        runtime.activate(provider.key());
        UUID tenant = UUID.randomUUID();
        runtime.bind(tenant, Capability.DISBURSE, provider.key(), 0);
        UUID transactionId = UUID.randomUUID();
        String idempotencyKey = "demo-processing-42";
        ExecutorService executor = Executors.newSingleThreadExecutor();
        PaymentGateway gateway = new PaymentGateway(executor, Duration.ofSeconds(1));
        try {
            var result = gateway.disburse(runtime, provider.key(), new DisbursementRequest(transactionId,
                    tenant, UUID.randomUUID(), new java.math.BigDecimal("125.00"),
                    Currency.getInstance("CNY"), idempotencyKey), context());
            assertEquals(PaymentStatus.PROCESSING, result.status());
            assertEquals(PaymentStatus.PROCESSING, gateway.reconcile(runtime, provider.key(),
                    transactionId, idempotencyKey, null, context()).status());
            assertEquals(PaymentStatus.FAILED, gateway.reconcile(runtime, provider.key(),
                    transactionId, "demo-failed-42", null, context()).status());
            assertEquals(PaymentStatus.UNKNOWN, gateway.reconcile(runtime, provider.key(),
                    transactionId, "demo-unknown-42", null, context()).status());
            assertEquals(PaymentStatus.SUCCEEDED, gateway.reconcile(runtime, provider.key(),
                    transactionId, "demo-success-42", null, context()).status());
        } finally {
            runtime.unbind(tenant, Capability.DISBURSE, 1);
            executor.shutdownNow();
        }
    }

    @Test
    void loadsAndReconcilesExternalBetaPayArtifact() throws Exception {
        Files.copy(Path.of(System.getProperty("finbridge.root"), "plugins", "betapay",
                "target", BETA_JAR), plugins.resolve(BETA_JAR));
        PluginRuntime runtime = new PluginRuntime(plugins, new ProviderFreeParent(getClass().getClassLoader()), 1);
        PluginHandle provider = runtime.install(BETA_JAR, new PluginContext(Map.of()));
        assertEquals(new PluginKey("betapay", "1.0.0"), provider.key());
        runtime.activate(provider.key());
        UUID tenant = UUID.randomUUID();
        runtime.bind(tenant, Capability.DISBURSE, provider.key(), 0);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            PaymentGateway gateway = new PaymentGateway(executor, Duration.ofSeconds(1));
            UUID transactionId = UUID.randomUUID();
            var result = gateway.disburse(runtime, provider.key(), new DisbursementRequest(transactionId,
                    tenant, UUID.randomUUID(), new java.math.BigDecimal("125.00"),
                    Currency.getInstance("CNY"), "beta-client-key"), context());
            assertEquals(PaymentStatus.SUCCEEDED, result.status());
            var reconciled = gateway.reconcile(runtime, provider.key(), transactionId,
                    "beta-client-key", null, context());
            assertEquals(PaymentStatus.SUCCEEDED, reconciled.status());
            assertEquals(result.providerTransactionId(), reconciled.providerTransactionId());
        } finally {
            runtime.unbind(tenant, Capability.DISBURSE, 1);
        }
    }

    @Test
    void mapsMissingProviderAndProviderFailureToUnknownAndReleasesLease() {
        UUID tenant = UUID.randomUUID();
        PluginKey owner = new PluginKey("missing-pay", "1.0");
        PluginRuntime runtime = mock(PluginRuntime.class);
        when(runtime.plugin(owner)).thenThrow(new IllegalArgumentException("missing"));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            PaymentGateway gateway = new PaymentGateway(executor, Duration.ofSeconds(1));
            assertEquals(PaymentStatus.UNKNOWN, gateway.disburse(runtime, owner,
                    request(tenant), context()).status());
            assertEquals(PaymentStatus.UNKNOWN, gateway.reconcile(runtime, owner, UUID.randomUUID(),
                    "idempotency", null, context()).status());
        }

        PluginInvocationLease lease = mock(PluginInvocationLease.class);
        PluginHandle handle = mock(PluginHandle.class);
        PluginRuntime failingRuntime = mock(PluginRuntime.class);
        when(failingRuntime.plugin(owner)).thenReturn(handle);
        when(handle.acquire(io.github.finbridge.runtime.InvocationKind.BUSINESS)).thenReturn(lease);
        when(lease.invoke(any())).thenThrow(new IllegalStateException("synthetic failure"));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var failure = new PaymentGateway(executor, Duration.ofSeconds(1))
                    .disburse(failingRuntime, owner, request(tenant), context());
            assertEquals("PAYMENT_PROVIDER_FAILURE", failure.diagnosticCode());
            verify(lease, timeout(1000)).close();
        }
    }

    @Test
    void executorRejectionAndQueuedTimeoutReturnUnknownAndReleaseLeases() throws Exception {
        UUID tenant = UUID.randomUUID();
        PluginKey owner = new PluginKey("synthetic-pay", "1");
        PluginInvocationLease rejectedLease = mock(PluginInvocationLease.class);
        PluginHandle rejectedHandle = mock(PluginHandle.class);
        PluginRuntime runtime = mock(PluginRuntime.class);
        when(runtime.plugin(owner)).thenReturn(rejectedHandle);
        when(rejectedHandle.acquire(io.github.finbridge.runtime.InvocationKind.BUSINESS))
                .thenReturn(rejectedLease);
        ThreadPoolExecutor stopped = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        stopped.shutdown();
        var saturated = new PaymentGateway(stopped, Duration.ofSeconds(1))
                .disburse(runtime, owner, request(tenant), context());
        assertEquals("PAYMENT_EXECUTOR_SATURATED", saturated.diagnosticCode());
        verify(rejectedLease).close();

        PluginInvocationLease queuedLease = mock(PluginInvocationLease.class);
        PluginHandle queuedHandle = mock(PluginHandle.class);
        when(runtime.plugin(owner)).thenReturn(queuedHandle);
        when(queuedHandle.acquire(io.github.finbridge.runtime.InvocationKind.BUSINESS))
                .thenReturn(queuedLease);
        ThreadPoolExecutor busy = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        busy.execute(() -> {
            started.countDown();
            await(release);
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        try {
            var timeout = new PaymentGateway(busy, Duration.ofMillis(20))
                    .disburse(runtime, owner, request(tenant), context());
            assertEquals("PAYMENT_PROVIDER_TIMEOUT", timeout.diagnosticCode());
        } finally {
            release.countDown();
            busy.shutdownNow();
        }
        verify(queuedLease).close();
    }

    @Test
    void rejectsNonpositiveTimeoutAndRestoresCallerInterrupt() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentGateway(Executors.newSingleThreadExecutor(), Duration.ZERO));
        UUID tenant = UUID.randomUUID();
        PluginKey owner = new PluginKey("interrupt-pay", "1");
        PluginInvocationLease lease = mock(PluginInvocationLease.class);
        PluginHandle handle = mock(PluginHandle.class);
        PluginRuntime runtime = mock(PluginRuntime.class);
        when(runtime.plugin(owner)).thenReturn(handle);
        when(handle.acquire(io.github.finbridge.runtime.InvocationKind.BUSINESS)).thenReturn(lease);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Thread.currentThread().interrupt();
            var interrupted = new PaymentGateway(executor, Duration.ofSeconds(1))
                    .disburse(runtime, owner, request(tenant), context());
            assertEquals("PAYMENT_CALL_INTERRUPTED", interrupted.diagnosticCode());
            assertTrue(Thread.currentThread().isInterrupted());
            Thread.interrupted();
            verify(lease, timeout(1000)).close();
        }
    }

    private static DisbursementRequest request(UUID tenant) {
        return new DisbursementRequest(UUID.randomUUID(), tenant, UUID.randomUUID(),
                new java.math.BigDecimal("50.00"), Currency.getInstance("CNY"), "gateway-test");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static OperationContext context() {
        return new OperationContext(UUID.randomUUID(), "payment-gateway-test",
                Instant.now().plusSeconds(2));
    }

    private static final class ProviderFreeParent extends ClassLoader {
        private static final String PAYMENT_SERVICE =
                "META-INF/services/io.github.finbridge.spi.payment.PaymentProvider";
        private static final String RISK_SERVICE =
                "META-INF/services/io.github.finbridge.spi.risk.RiskDataProvider";

        private ProviderFreeParent(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("io.github.finbridge.plugins.")) {
                throw new ClassNotFoundException("Provider must be loaded from its external JAR");
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
