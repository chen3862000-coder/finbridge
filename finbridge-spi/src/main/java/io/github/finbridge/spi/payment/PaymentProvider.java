package io.github.finbridge.spi.payment;

import io.github.finbridge.spi.FinBridgePlugin;
import io.github.finbridge.spi.OperationContext;

public interface PaymentProvider extends FinBridgePlugin {
    PaymentResult disburse(DisbursementRequest request, OperationContext context);

    PaymentResult collect(CollectionRequest request, OperationContext context);

    PaymentStatus queryStatus(PaymentTransactionRef transaction, OperationContext context);
}
