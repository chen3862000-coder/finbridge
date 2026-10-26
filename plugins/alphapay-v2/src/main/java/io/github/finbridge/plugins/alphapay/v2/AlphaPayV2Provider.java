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
        return PaymentResult.succeeded(Helper.providerMarker() + "-" + request.paymentTransactionId());
    }

    @Override
    public PaymentResult collect(CollectionRequest request, OperationContext context) {
        return PaymentResult.succeeded(Helper.providerMarker() + "-" + request.paymentTransactionId());
    }

    @Override
    public PaymentStatus queryStatus(PaymentTransactionRef transaction, OperationContext context) {
        return transaction.providerTransactionId().startsWith(Helper.providerMarker())
                ? PaymentStatus.SUCCEEDED : PaymentStatus.UNKNOWN;
    }
}
