package io.github.finbridge.lending.domain;

public enum ApplicationStatus {
    SUBMITTED,
    RISK_EVALUATING,
    APPROVED,
    REJECTED,
    MANUAL_REVIEW,
    ORIGINATION_IN_PROGRESS,
    ORIGINATED,
    CANCELLED
}
