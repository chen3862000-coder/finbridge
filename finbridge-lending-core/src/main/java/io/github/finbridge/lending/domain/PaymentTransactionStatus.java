package io.github.finbridge.lending.domain;

public enum PaymentTransactionStatus {
    SUBMISSION_IN_PROGRESS,
    PROCESSING,
    UNKNOWN,
    RECONCILIATION_REQUIRED,
    SUCCEEDED,
    FAILED;

    public boolean unresolved() {
        return this == SUBMISSION_IN_PROGRESS || this == PROCESSING || this == UNKNOWN
                || this == RECONCILIATION_REQUIRED;
    }

    public static PaymentTransactionStatus fromProvider(io.github.finbridge.spi.payment.PaymentStatus status) {
        return switch (java.util.Objects.requireNonNull(status, "status")) {
            case SUCCEEDED -> SUCCEEDED;
            case FAILED -> FAILED;
            case PROCESSING -> PROCESSING;
            case UNKNOWN -> RECONCILIATION_REQUIRED;
        };
    }
}
