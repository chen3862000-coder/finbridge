package io.github.finbridge.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.finbridge.runtime.PluginHandle;
import io.github.finbridge.runtime.PluginInvocationLease;
import io.github.finbridge.runtime.PluginRuntime;
import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.spi.risk.RiskDataRequest;
import io.github.finbridge.spi.risk.RiskDataType;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RiskDataGatewayFailureTest {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID EXECUTION = UUID.randomUUID();

    @Test
    void providerExceptionIsReturnedAsUnavailableOutcome() {
        PluginInvocationLease lease = lease();
        when(lease.invoke(any())).thenThrow(new IllegalStateException("synthetic provider failure"));
        PluginRuntime runtime = runtime(lease);
        try (ThreadPoolExecutor executor = executor()) {
            var result = gateway(runtime, executor).query(TENANT, RiskDataType.CREDIT_SCORE,
                    request(), context());

            assertEquals("PROVIDER_FAILURE", result.failureCode());
            assertEquals(EXECUTION, result.executionId());
        }
    }

    @Test
    void interruptedWaitRestoresInterruptFlagAndReleasesQueuedLease() throws Exception {
        PluginInvocationLease lease = lease();
        PluginRuntime runtime = runtime(lease);
        ThreadPoolExecutor executor = executor();
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> await(release));
        try {
            Thread.currentThread().interrupt();
            var result = gateway(runtime, executor).query(TENANT, RiskDataType.CREDIT_SCORE,
                    request(), context());

            assertEquals("CALL_INTERRUPTED", result.failureCode());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(lease).close();
        } finally {
            Thread.interrupted();
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static PluginRuntime runtime(PluginInvocationLease lease) {
        PluginRuntime runtime = mock(PluginRuntime.class);
        when(runtime.route(TENANT, Capability.CREDIT_SCORE,
                io.github.finbridge.runtime.InvocationKind.BUSINESS)).thenReturn(lease);
        return runtime;
    }

    private static PluginInvocationLease lease() {
        PluginInvocationLease lease = mock(PluginInvocationLease.class);
        PluginHandle handle = mock(PluginHandle.class);
        when(lease.handle()).thenReturn(handle);
        when(handle.key()).thenReturn(new PluginKey("synthetic-risk", "1.0.0"));
        return lease;
    }

    private static RiskDataGateway gateway(PluginRuntime runtime, ThreadPoolExecutor executor) {
        return new RiskDataGateway(runtime, executor, Duration.ofSeconds(1));
    }

    private static ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
    }

    private static RiskDataRequest request() {
        return new RiskDataRequest(UUID.randomUUID(), TENANT, "synthetic-applicant");
    }

    private static OperationContext context() {
        return new OperationContext(EXECUTION, "risk-failure-test", Instant.now().plusSeconds(2));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
