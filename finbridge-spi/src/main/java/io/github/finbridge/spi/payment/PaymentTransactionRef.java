package io.github.finbridge.spi.payment;

import java.util.Objects;
import java.util.UUID;

/** Identifies one payment for a status query using exactly one reference form. */
public record PaymentTransactionRef(String providerTransactionId, UUID paymentTransactionId,
                                    String idempotencyKey) {
    /** Creates a reference using the provider's previously returned transaction ID. */
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

    /** Creates a host-owned client reference for providers that have not returned an ID yet. */
    public static PaymentTransactionRef byClientReference(UUID paymentTransactionId,
            String idempotencyKey) {
        return new PaymentTransactionRef(null,
                Objects.requireNonNull(paymentTransactionId, "paymentTransactionId"),
                Objects.requireNonNull(idempotencyKey, "idempotencyKey"));
    }

    /** Returns whether this reference uses a provider transaction ID rather than client identity. */
    public boolean hasProviderTransactionId() {
        return providerTransactionId != null;
    }
}
