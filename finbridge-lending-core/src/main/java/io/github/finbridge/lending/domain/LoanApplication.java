package io.github.finbridge.lending.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record LoanApplication(UUID applicationId, UUID tenantId, String applicantId,
                              String productCode, int productVersion, BigDecimal requestedAmount,
                              Currency currency, int termMonths, ApplicationStatus status,
                              Instant createdAt, Instant updatedAt, long version) {
    public LoanApplication {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(tenantId, "tenantId");
        requireText(applicantId, "applicantId");
        requireText(productCode, "productCode");
        Objects.requireNonNull(requestedAmount, "requestedAmount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (productVersion < 1 || requestedAmount.signum() <= 0 || termMonths <= 0 || version < 0) {
            throw new IllegalArgumentException("Invalid loan application values");
        }
    }

    public LoanApplication transitionTo(ApplicationStatus next, Instant at) {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(at, "at");
        if (!allows(next)) {
            throw new IllegalStateException("Invalid application transition " + status + " -> " + next);
        }
        return new LoanApplication(applicationId, tenantId, applicantId, productCode, productVersion,
                requestedAmount, currency, termMonths, next, createdAt, at, version + 1);
    }

    private boolean allows(ApplicationStatus next) {
        return switch (status) {
            case SUBMITTED -> next == ApplicationStatus.RISK_EVALUATING
                    || next == ApplicationStatus.CANCELLED;
            case RISK_EVALUATING -> next == ApplicationStatus.APPROVED
                    || next == ApplicationStatus.REJECTED || next == ApplicationStatus.MANUAL_REVIEW;
            case APPROVED -> next == ApplicationStatus.ORIGINATION_IN_PROGRESS
                    || next == ApplicationStatus.CANCELLED;
            case ORIGINATION_IN_PROGRESS -> next == ApplicationStatus.ORIGINATED;
            case REJECTED, MANUAL_REVIEW, ORIGINATED, CANCELLED -> false;
        };
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
