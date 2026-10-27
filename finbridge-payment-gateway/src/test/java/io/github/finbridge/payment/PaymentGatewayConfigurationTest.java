package io.github.finbridge.payment;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PaymentGatewayConfigurationTest {
    @Test
    void createsDaemonWorkersAndWiresConfiguredGateway() throws Exception {
        PaymentGatewayConfiguration configuration = new PaymentGatewayConfiguration();
        ExecutorService executor = configuration.paymentProviderExecutor();
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        try {
            executor.execute(() -> {
                worker.set(Thread.currentThread());
                ran.countDown();
            });
            assertTrue(ran.await(2, TimeUnit.SECONDS));
            assertTrue(worker.get().isDaemon());
            assertTrue(worker.get().getName().startsWith("finbridge-payment-"));
            assertNotNull(configuration.paymentGateway(executor, Duration.ofMillis(50)));
        } finally {
            executor.shutdownNow();
        }
    }
}
