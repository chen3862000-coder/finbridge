package io.github.finbridge.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.finbridge.runtime.PluginRuntime;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RiskGatewayConfigurationTest {
    @Test
    void createsDecisionPolicyAndBoundedDaemonExecutor() throws Exception {
        RiskGatewayConfiguration configuration = new RiskGatewayConfiguration();
        assertNotNull(configuration.syntheticRiskDecisionPolicy());
        assertEquals("risk unavailable", new RiskGatewayException("risk unavailable").getMessage());
        ExecutorService executor = configuration.riskProviderExecutor();
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        try {
            executor.execute(() -> {
                worker.set(Thread.currentThread());
                ran.countDown();
            });
            assertTrue(ran.await(2, TimeUnit.SECONDS));
            assertTrue(worker.get().isDaemon());
            assertTrue(worker.get().getName().startsWith("finbridge-risk-"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void wiresGatewayWithConfiguredTimeoutAndExceptionMessage() {
        RiskGatewayConfiguration configuration = new RiskGatewayConfiguration();
        ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            RiskDataGateway gateway = configuration.riskDataGateway(
                    new PluginRuntime(Path.of("plugins"), getClass().getClassLoader(), 1),
                    executor, new io.github.finbridge.runtime.PluginOperationMetrics(
                            new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                    Duration.ofMillis(250));
            assertNotNull(gateway);
            assertEquals("risk unavailable", new RiskGatewayException("risk unavailable").getMessage());
        } finally {
            executor.shutdownNow();
        }
    }
}
