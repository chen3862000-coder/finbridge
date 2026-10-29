package io.github.finbridge.spi.payment;

import io.github.finbridge.spi.FinBridgePlugin;
import io.github.finbridge.spi.OperationContext;

/** Provider contract for disbursement and collection effects and their later reconciliation. */
public interface PaymentProvider extends FinBridgePlugin {
    /** Submits a disbursement once; uncertain results must be reported as {@link PaymentStatus#UNKNOWN}. */
    PaymentResult disburse(DisbursementRequest request, OperationContext context);

    /** Submits a collection request using the operation's stable idempotency reference. */
    PaymentResult collect(CollectionRequest request, OperationContext context);

    /** Queries an existing operation without creating or resubmitting a payment effect. */
    PaymentResult queryStatus(PaymentTransactionRef transaction, OperationContext context);
}
