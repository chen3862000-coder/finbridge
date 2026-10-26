package io.github.finbridge.spi.payment;

import java.util.Objects;

public record PaymentTransactionRef(String providerTransactionId) {
    public PaymentTransactionRef {
        Objects.requireNonNull(providerTransactionId, "providerTransactionId");
        if (providerTransactionId.isBlank()) {
            throw new IllegalArgumentException("providerTransactionId must not be blank");
        }
    }
}
