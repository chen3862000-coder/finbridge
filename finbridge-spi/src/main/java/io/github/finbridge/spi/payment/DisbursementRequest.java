package io.github.finbridge.spi.payment;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record DisbursementRequest(UUID paymentTransactionId, UUID tenantId, UUID loanId,
                                  BigDecimal amount, Currency currency, String idempotencyKey) {
    public DisbursementRequest {
        Objects.requireNonNull(paymentTransactionId, "paymentTransactionId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(loanId, "loanId");
        requireAmount(amount);
        Objects.requireNonNull(currency, "currency");
        requireKey(idempotencyKey);
    }

    static void requireAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }

    static void requireKey(String key) {
        Objects.requireNonNull(key, "idempotencyKey");
        if (key.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }
}
