package io.github.finbridge.lending.application;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record CreateLoanApplicationCommand(UUID tenantId, String applicantId, String productCode,
                                           BigDecimal requestedAmount, Currency currency,
                                           int termMonths, String idempotencyKey) {
    public CreateLoanApplicationCommand {
        Objects.requireNonNull(tenantId, "tenantId");
        requireText(applicantId, "applicantId");
        requireText(productCode, "productCode");
        Objects.requireNonNull(requestedAmount, "requestedAmount");
        Objects.requireNonNull(currency, "currency");
        requireText(idempotencyKey, "idempotencyKey");
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
