package io.github.finbridge.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.github.finbridge.runtime.PluginHandle;
import io.github.finbridge.runtime.PluginRuntime;
import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.risk.BlacklistValue;
import io.github.finbridge.spi.risk.CreditScoreValue;
import io.github.finbridge.spi.risk.FraudSignalValue;
import io.github.finbridge.spi.risk.RiskDataRequest;
import io.github.finbridge.spi.risk.RiskDataType;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RiskDataGatewayIntegrationTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SCORE_JAR = "creditscorex-0.1.0-SNAPSHOT-jar-with-dependencies.jar";
    private static final String BLACKLIST_JAR = "blacklisty-0.1.0-SNAPSHOT-jar-with-dependencies.jar";
    private static final String FRAUD_JAR = "fraudsignalz-0.1.0-SNAPSHOT-jar-with-dependencies.jar";

    @TempDir
    Path plugins;

    @Test
    void loadsAllRiskPluginsAsExternalJarsAndReturnsTypedSyntheticValues() throws Exception {
        copyJar("creditscorex", SCORE_JAR);
        copyJar("blacklisty", BLACKLIST_JAR);
        copyJar("fraudsignalz", FRAUD_JAR);
        PluginRuntime runtime = runtime();
        PluginHandle score = runtime.installRiskProvider(SCORE_JAR,
                new io.github.finbridge.spi.PluginContext(Map.of()),
                RiskDataType.CREDIT_SCORE);
        PluginHandle blacklist = runtime.installRiskProvider(BLACKLIST_JAR,
                new io.github.finbridge.spi.PluginContext(Map.of()), RiskDataType.BLACKLIST);
        PluginHandle fraud = runtime.installRiskProvider(FRAUD_JAR,
                new io.github.finbridge.spi.PluginContext(Map.of()), RiskDataType.FRAUD_SIGNAL);
        runtime.activate(score.key());
        runtime.activate(blacklist.key());
        runtime.activate(fraud.key());
        runtime.bind(TENANT, Capability.CREDIT_SCORE, score.key(), 0);
        runtime.bind(TENANT, Capability.BLACKLIST, blacklist.key(), 0);
        runtime.bind(TENANT, Capability.FRAUD_SIGNAL, fraud.key(), 0);

        try (ThreadPoolExecutor executor = executor()) {
            RiskDataGateway gateway = new RiskDataGateway(runtime, executor, Duration.ofSeconds(1));
            var outcomes = gateway.collect(TENANT, UUID.randomUUID(), "synthetic-low-score-blacklisted-critical-fraud",
                    "risk-integration");
            assertEquals(550, ((CreditScoreValue) outcomes.get(RiskDataType.CREDIT_SCORE)
                    .result().value()).score());
            assertTrue(((BlacklistValue) outcomes.get(RiskDataType.BLACKLIST).result().value()).hit());
            assertEquals(FraudSignalValue.Severity.CRITICAL,
                    ((FraudSignalValue) outcomes.get(RiskDataType.FRAUD_SIGNAL).result().value()).severity());
            assertNotNull(outcomes.get(RiskDataType.CREDIT_SCORE).pluginKey());
        }
    }

    @Test
    void absentCapabilityIsUnavailableAndDoesNotAcquireLease() throws Exception {
        copyJar("creditscorex", SCORE_JAR);
        copyJar("fraudsignalz", FRAUD_JAR);
        PluginRuntime runtime = runtime();
        PluginHandle plugin = runtime.installRiskProvider(SCORE_JAR,
                new io.github.finbridge.spi.PluginContext(Map.of()), RiskDataType.CREDIT_SCORE);
        runtime.activate(plugin.key());
        try (ThreadPoolExecutor executor = executor()) {
            var result = new RiskDataGateway(runtime, executor, Duration.ofSeconds(1))
                    .query(TENANT, RiskDataType.CREDIT_SCORE,
                            new RiskDataRequest(UUID.randomUUID(), TENANT, "synthetic"), context());
            assertEquals("CAPABILITY_UNAVAILABLE", result.failureCode());
            assertEquals(0, plugin.activeInvocations());
        }
        PluginHandle wrongType = runtime.installRiskProvider(FRAUD_JAR,
                new io.github.finbridge.spi.PluginContext(Map.of()), RiskDataType.FRAUD_SIGNAL);
        runtime.activate(wrongType.key());
        try (ThreadPoolExecutor executor = executor()) {
            var result = new RiskDataGateway(runtime, executor, Duration.ofSeconds(1))
                    .query(TENANT, RiskDataType.CREDIT_SCORE,
                            new RiskDataRequest(UUID.randomUUID(), TENANT, "synthetic"), context());
            assertEquals("CAPABILITY_UNAVAILABLE", result.failureCode());
        }
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new RiskDataGateway(mock(PluginRuntime.class), executor(), Duration.ZERO));
    }

    @Test
    void executorSaturationReturnsUnavailableAndReleasesInvocationLease() throws Exception {
        PluginRuntime runtime = installScoreProvider();
        PluginHandle provider = runtime.plugins().getFirst();
        ThreadPoolExecutor executor = executor();
        executor.shutdown();

        var outcome = new RiskDataGateway(runtime, executor, Duration.ofSeconds(1)).query(TENANT,
                RiskDataType.CREDIT_SCORE,
                new RiskDataRequest(UUID.randomUUID(), TENANT, "synthetic"), context());

        assertEquals("RISK_EXECUTOR_SATURATED", outcome.failureCode());
        assertEquals(0, provider.activeInvocations());
    }

    @Test
    void queuedProviderTimeoutCancelsCallAndReleasesLease() throws Exception {
        PluginRuntime runtime = installScoreProvider();
        PluginHandle provider = runtime.plugins().getFirst();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        executor.execute(() -> await(release));
        try {
            var outcome = new RiskDataGateway(runtime, executor, Duration.ofMillis(20)).query(TENANT,
                    RiskDataType.CREDIT_SCORE,
                    new RiskDataRequest(UUID.randomUUID(), TENANT, "synthetic"), context());
            assertEquals("PROVIDER_TIMEOUT", outcome.failureCode());
            assertEquals(0, provider.activeInvocations());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void copyJar(String module, String fileName) throws Exception {
        Path source = Path.of(System.getProperty("finbridge.root"), "plugins", module,
                "target", fileName);
        Files.copy(source, plugins.resolve(fileName));
    }

    private PluginRuntime runtime() {
        return new PluginRuntime(plugins, new ProviderFreeParent(getClass().getClassLoader()), 1);
    }

    private PluginRuntime installScoreProvider() throws Exception {
        copyJar("creditscorex", SCORE_JAR);
        PluginRuntime runtime = runtime();
        PluginHandle score = runtime.installRiskProvider(SCORE_JAR,
                new io.github.finbridge.spi.PluginContext(Map.of()), RiskDataType.CREDIT_SCORE);
        runtime.activate(score.key());
        runtime.bind(TENANT, Capability.CREDIT_SCORE, score.key(), 0);
        return runtime;
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
    }

    private static OperationContext context() {
        return new OperationContext(UUID.randomUUID(), "risk-test", Instant.now().plusSeconds(2));
    }

    private static final class ProviderFreeParent extends ClassLoader {
        private static final String PROVIDER_PREFIX = "io.github.finbridge.plugins.";
        private static final String SERVICE = "META-INF/services/io.github.finbridge.spi.risk.RiskDataProvider";

        private ProviderFreeParent(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(PROVIDER_PREFIX)) {
                throw new ClassNotFoundException("Risk providers must load from plugin JAR: " + name);
            }
            return super.loadClass(name, resolve);
        }

        @Override
        public URL getResource(String name) {
            return SERVICE.equals(name) ? null : super.getResource(name);
        }

        @Override
        public java.util.Enumeration<URL> getResources(String name) throws java.io.IOException {
            return SERVICE.equals(name) ? java.util.Collections.emptyEnumeration()
                    : super.getResources(name);
        }
    }
}
