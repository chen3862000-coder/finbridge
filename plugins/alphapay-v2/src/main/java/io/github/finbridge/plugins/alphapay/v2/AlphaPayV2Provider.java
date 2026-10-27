package io.github.finbridge.plugins.alphapay.v2;

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

public final class AlphaPayV2Provider implements PaymentProvider {
    private static final PluginDescriptor PLUGIN_DESCRIPTOR = new PluginDescriptor(
            "alphapay", "ALPHAPAY", "2.0.0", "1.0", PluginType.PAYMENT,
            Set.of(Capability.DISBURSE, Capability.COLLECT, Capability.QUERY_STATUS),
            "AlphaPay v2", "Synthetic payment provider v2 with a changed private response helper");

    @Override
    public PluginDescriptor descriptor() {
        return PLUGIN_DESCRIPTOR;
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
