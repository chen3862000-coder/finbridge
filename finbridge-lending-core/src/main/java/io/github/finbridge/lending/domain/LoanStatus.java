package io.github.finbridge.lending.domain;

public enum LoanStatus {
    PENDING_DISBURSEMENT,
    DISBURSEMENT_PROCESSING,
    ACTIVE,
    DISBURSEMENT_FAILED,
    CANCELLED,
    CLOSED
}
