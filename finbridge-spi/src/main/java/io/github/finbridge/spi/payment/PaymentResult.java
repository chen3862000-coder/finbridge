package io.github.finbridge.spi.payment;

import io.github.finbridge.spi.ProviderErrorCategory;
import java.util.Objects;

/** Provider outcome; only a confirmed success may carry settlement identity as success. */
public record PaymentResult(PaymentStatus status, String providerTransactionId,
                            ProviderErrorCategory errorCategory, String providerCode) {
    public PaymentResult {
        Objects.requireNonNull(status, "status");
        if (status == PaymentStatus.SUCCEEDED && providerTransactionId == null) {
            throw new IllegalArgumentException("Successful payment requires provider transaction ID");
        }
        if (providerTransactionId != null && providerTransactionId.isBlank()) {
            throw new IllegalArgumentException("providerTransactionId must not be blank");
        }
        if (providerCode != null && providerCode.isBlank()) {
            throw new IllegalArgumentException("providerCode must not be blank");
        }
    }

    /** Creates a confirmed success with the provider reference required for later reconciliation. */
    public static PaymentResult succeeded(String providerTransactionId) {
        return new PaymentResult(PaymentStatus.SUCCEEDED, providerTransactionId, null, null);
    }
}
