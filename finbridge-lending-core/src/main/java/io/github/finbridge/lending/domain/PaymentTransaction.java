package io.github.finbridge.lending.domain;

import io.github.finbridge.spi.PluginKey;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record PaymentTransaction(UUID paymentTransactionId, UUID tenantId, UUID loanId,
        String operationType, String idempotencyKey, String requestFingerprint,
        BigDecimal amount, Currency currency, PluginKey owner, String providerTransactionId,
        String reconciliationKey, PaymentTransactionStatus status, UUID dispatchAttemptId, Instant dispatchClaimedAt,
        Instant updatedAt, long version, String diagnosticCode) {
    public PaymentTransaction {
        Objects.requireNonNull(paymentTransactionId, "paymentTransactionId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(operationType, "operationType");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(reconciliationKey, "reconciliationKey");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(dispatchAttemptId, "dispatchAttemptId");
        Objects.requireNonNull(dispatchClaimedAt, "dispatchClaimedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (amount.signum() <= 0 || version < 0 || idempotencyKey.isBlank()
                || !requestFingerprint.matches("[a-f0-9]{64}")
                || (providerTransactionId != null && providerTransactionId.isBlank())
                || reconciliationKey.isBlank()) {
            throw new IllegalArgumentException("Invalid payment transaction values");
        }
        if (status == PaymentTransactionStatus.SUCCEEDED && providerTransactionId == null) {
            throw new IllegalArgumentException("Successful payment requires provider reference");
        }
    }
}
