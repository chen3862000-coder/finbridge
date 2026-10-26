package io.github.finbridge.plugins.alphapay.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.payment.DisbursementRequest;
import io.github.finbridge.spi.payment.CollectionRequest;
import io.github.finbridge.spi.payment.PaymentResult;
import io.github.finbridge.spi.payment.PaymentStatus;
import io.github.finbridge.spi.payment.PaymentTransactionRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AlphaPayV1ProviderTest {
    @Test
    void disbursesWithV1PrivateMarkerAndCanQueryItsOwnTransaction() {
        AlphaPayV1Provider provider = new AlphaPayV1Provider();
        assertEquals("alphapay", provider.descriptor().pluginId());
        UUID transactionId = UUID.randomUUID();
        PaymentResult result = provider.disburse(new DisbursementRequest(transactionId, UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("1000.00"), Currency.getInstance("CNY"), "key"),
                context());
        assertEquals("alphapay-private-v1-" + transactionId, result.providerTransactionId());
        assertEquals(PaymentStatus.SUCCEEDED,
                provider.queryStatus(new PaymentTransactionRef(result.providerTransactionId()), context()));
        assertEquals(PaymentStatus.UNKNOWN,
                provider.queryStatus(new PaymentTransactionRef("alphapay-private-v2-old"), context()));
        CollectionRequest collection = new CollectionRequest(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("20.00"),
                Currency.getInstance("CNY"), "collect-key");
        PaymentResult collectionResult = provider.collect(collection, context());
        assertEquals("alphapay-private-v1-" + collection.paymentTransactionId(),
                collectionResult.providerTransactionId());
    }

    private static OperationContext context() {
        return new OperationContext(UUID.randomUUID(), "test", Instant.now().plusSeconds(2));
    }
}
