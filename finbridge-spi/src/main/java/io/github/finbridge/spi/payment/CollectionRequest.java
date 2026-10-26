package io.github.finbridge.spi.payment;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record CollectionRequest(UUID paymentTransactionId, UUID tenantId, UUID loanId,
                                UUID installmentId, BigDecimal amount, Currency currency,
                                String idempotencyKey) {
    public CollectionRequest {
        Objects.requireNonNull(paymentTransactionId, "paymentTransactionId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(installmentId, "installmentId");
        DisbursementRequest.requireAmount(amount);
        Objects.requireNonNull(currency, "currency");
        DisbursementRequest.requireKey(idempotencyKey);
    }
}
