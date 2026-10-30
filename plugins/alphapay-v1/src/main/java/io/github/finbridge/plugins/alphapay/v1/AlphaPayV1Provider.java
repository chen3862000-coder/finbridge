package io.github.finbridge.plugins.alphapay.v1;

import io.github.finbridge.plugins.privateapi.Helper;
import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.PluginDescriptor;
import io.github.finbridge.spi.PluginType;
import io.github.finbridge.spi.payment.CollectionRequest;
import io.github.finbridge.spi.payment.DisbursementRequest;
import io.github.finbridge.spi.payment.PaymentProvider;
import io.github.finbridge.spi.payment.PaymentResult;
import io.github.finbridge.spi.payment.PaymentStatus;
import io.github.finbridge.spi.payment.PaymentTransactionRef;
import java.util.Set;

public final class AlphaPayV1Provider implements PaymentProvider {
    private static final String INITIALIZE_DELAY = "test.lifecycle.initialize-delay-ms";
    private static final String HEALTH_DELAY = "test.lifecycle.health-delay-ms";
    private static final String CLOSE_DELAY = "test.lifecycle.close-delay-ms";
    private static final String CLOSE_FAIL = "test.lifecycle.close-fail";
    private volatile long healthDelayMillis;
    private volatile long closeDelayMillis;
    private volatile boolean closeFails;
    private static final PluginDescriptor PLUGIN_DESCRIPTOR = new PluginDescriptor(
            "alphapay", "ALPHAPAY", "1.0.0", "1.0", PluginType.PAYMENT,
            Set.of(Capability.DISBURSE, Capability.COLLECT, Capability.QUERY_STATUS),
            "AlphaPay v1", "Synthetic synchronous payment provider v1");

    @Override
    public PluginDescriptor descriptor() {
        return PLUGIN_DESCRIPTOR;
    }

    @Override
    public void initialize(io.github.finbridge.spi.PluginContext context) {
        delay(context.configuration().get(INITIALIZE_DELAY));
        healthDelayMillis = delayValue(context.configuration().get(HEALTH_DELAY));
        closeDelayMillis = delayValue(context.configuration().get(CLOSE_DELAY));
        closeFails = Boolean.parseBoolean(context.configuration().get(CLOSE_FAIL));
    }

    @Override
    public io.github.finbridge.spi.PluginHealth healthCheck() {
        delay(healthDelayMillis);
        return io.github.finbridge.spi.PluginHealth.healthyStatus();
    }

    @Override
    public void close() {
        delay(closeDelayMillis);
        if (closeFails) {
            throw new IllegalStateException("synthetic close failure");
        }
    }

    private static long delayValue(String value) {
        return value == null ? 0 : Long.parseLong(value);
    }

    private static void delay(String value) {
        delay(delayValue(value));
    }

    private static void delay(long millis) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            try {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(deadline - System.nanoTime());
            } catch (InterruptedException ignored) {
                // This synthetic provider fixture deliberately ignores cancellation for timeout tests.
            }
        }
    }

    @Override
    public PaymentResult disburse(DisbursementRequest request, OperationContext context) {
        return resultFor(request.paymentTransactionId(), request.idempotencyKey());
    }

    @Override
    public PaymentResult collect(CollectionRequest request, OperationContext context) {
        return PaymentResult.succeeded(Helper.providerMarker() + "-" + request.paymentTransactionId());
    }

    @Override
    public PaymentResult queryStatus(PaymentTransactionRef transaction, OperationContext context) {
        if (transaction.hasProviderTransactionId()) {
            return transaction.providerTransactionId().startsWith(Helper.providerMarker())
                    ? PaymentResult.succeeded(transaction.providerTransactionId())
                    : new PaymentResult(PaymentStatus.UNKNOWN, null, null, null);
        }
        return resultFor(transaction.paymentTransactionId(), transaction.idempotencyKey());
    }

    private static PaymentResult resultFor(java.util.UUID transactionId, String idempotencyKey) {
        PaymentStatus status = syntheticStatus(idempotencyKey);
        return status == PaymentStatus.SUCCEEDED
                ? PaymentResult.succeeded(Helper.providerMarker() + "-" + transactionId)
                : new PaymentResult(status, null, null, null);
    }

    private static PaymentStatus syntheticStatus(String idempotencyKey) {
        if (idempotencyKey.startsWith("demo-processing")) {
            return PaymentStatus.PROCESSING;
        }
        if (idempotencyKey.startsWith("demo-failed")) {
            return PaymentStatus.FAILED;
        }
        if (idempotencyKey.startsWith("demo-unknown")) {
            return PaymentStatus.UNKNOWN;
        }
        return PaymentStatus.SUCCEEDED;
    }
}
