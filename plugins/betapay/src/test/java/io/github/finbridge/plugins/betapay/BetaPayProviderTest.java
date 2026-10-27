package io.github.finbridge.plugins.betapay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.payment.CollectionRequest;
import io.github.finbridge.spi.payment.DisbursementRequest;
import io.github.finbridge.spi.payment.PaymentStatus;
import io.github.finbridge.spi.payment.PaymentTransactionRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BetaPayProviderTest {
    @Test
    void dispatchesAndReconcilesSyntheticPaymentsByProviderOrClientReference() {
        BetaPayProvider provider = new BetaPayProvider();
        UUID transactionId = UUID.randomUUID();
        var result = provider.disburse(new DisbursementRequest(transactionId, UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("125.00"), Currency.getInstance("CNY"), "beta-key"),
                context());

        assertEquals("betapay", provider.descriptor().pluginId());
        assertEquals("betapay-transaction-" + transactionId, result.providerTransactionId());
        assertEquals(PaymentStatus.SUCCEEDED, provider.queryStatus(
                new PaymentTransactionRef(result.providerTransactionId()), context()).status());
        assertEquals(PaymentStatus.SUCCEEDED, provider.queryStatus(PaymentTransactionRef
                .byClientReference(transactionId, "beta-key"), context()).status());
        assertEquals(PaymentStatus.UNKNOWN, provider.queryStatus(PaymentTransactionRef
                .byClientReference(transactionId, "demo-unknown-case"), context()).status());
        assertEquals(PaymentStatus.PROCESSING, provider.disburse(new DisbursementRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("125.00"),
                Currency.getInstance("CNY"), "demo-processing-case"), context()).status());
        assertEquals(PaymentStatus.FAILED, provider.disburse(new DisbursementRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("125.00"),
                Currency.getInstance("CNY"), "demo-failed-case"), context()).status());
        assertEquals(PaymentStatus.UNKNOWN, provider.disburse(new DisbursementRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("125.00"),
                Currency.getInstance("CNY"), "demo-unknown-case"), context()).status());
        var collection = new CollectionRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("20.00"), Currency.getInstance("CNY"), "collect-key");
        assertEquals("betapay-transaction-" + collection.paymentTransactionId(),
                provider.collect(collection, context()).providerTransactionId());
    }

    private static OperationContext context() {
        return new OperationContext(UUID.randomUUID(), "betapay-test", Instant.now().plusSeconds(2));
    }
}
