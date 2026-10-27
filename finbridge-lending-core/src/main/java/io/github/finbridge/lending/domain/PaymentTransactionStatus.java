package io.github.finbridge.lending.domain;

public enum PaymentTransactionStatus {
    SUBMISSION_IN_PROGRESS,
    PROCESSING,
    UNKNOWN,
    SUCCEEDED,
    FAILED;

    public boolean unresolved() {
        return this == SUBMISSION_IN_PROGRESS || this == PROCESSING || this == UNKNOWN;
    }
}
