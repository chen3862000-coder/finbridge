package io.github.finbridge.plugins.betapay;

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

public final class BetaPayProvider implements PaymentProvider {
    private static final String PROVIDER_PREFIX = "betapay-transaction-";
    private static final PluginDescriptor PLUGIN_DESCRIPTOR = new PluginDescriptor(
            "betapay", "BETAPAY", "1.0.0", "1.0", PluginType.PAYMENT,
            Set.of(Capability.DISBURSE, Capability.COLLECT, Capability.QUERY_STATUS),
            "BetaPay", "Synthetic payment provider for durable disbursement demonstrations");

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
        return PaymentResult.succeeded(PROVIDER_PREFIX + request.paymentTransactionId());
    }

    @Override
    public PaymentResult queryStatus(PaymentTransactionRef transaction, OperationContext context) {
        if (transaction.hasProviderTransactionId()) {
            return transaction.providerTransactionId().startsWith(PROVIDER_PREFIX)
                    ? PaymentResult.succeeded(transaction.providerTransactionId())
                    : unresolved(PaymentStatus.UNKNOWN);
        }
        return resultFor(transaction.paymentTransactionId(), transaction.idempotencyKey());
    }

    private static PaymentResult resultFor(java.util.UUID transactionId, String idempotencyKey) {
        PaymentStatus status = syntheticStatus(idempotencyKey);
        return status == PaymentStatus.SUCCEEDED
                ? PaymentResult.succeeded(PROVIDER_PREFIX + transactionId)
                : unresolved(status);
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

    private static PaymentResult unresolved(PaymentStatus status) {
        return new PaymentResult(status, null, null, null);
    }
}
