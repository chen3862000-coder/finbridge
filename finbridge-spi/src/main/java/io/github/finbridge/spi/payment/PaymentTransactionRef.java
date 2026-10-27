package io.github.finbridge.spi.payment;

import java.util.Objects;
import java.util.UUID;

public record PaymentTransactionRef(String providerTransactionId, UUID paymentTransactionId,
                                    String idempotencyKey) {
    public PaymentTransactionRef(String providerTransactionId) {
        this(Objects.requireNonNull(providerTransactionId, "providerTransactionId"), null, null);
    }

    public PaymentTransactionRef {
        boolean providerReference = providerTransactionId != null;
        boolean clientReference = paymentTransactionId != null && idempotencyKey != null
                && !idempotencyKey.isBlank();
        if (providerReference == clientReference) {
            throw new IllegalArgumentException("Specify exactly one provider or client reference");
        }
        if (providerReference && providerTransactionId.isBlank()) {
            throw new IllegalArgumentException("providerTransactionId must not be blank");
        }
    }

    public static PaymentTransactionRef byClientReference(UUID paymentTransactionId,
            String idempotencyKey) {
        return new PaymentTransactionRef(null,
                Objects.requireNonNull(paymentTransactionId, "paymentTransactionId"),
                Objects.requireNonNull(idempotencyKey, "idempotencyKey"));
    }

    public boolean hasProviderTransactionId() {
        return providerTransactionId != null;
    }
}
