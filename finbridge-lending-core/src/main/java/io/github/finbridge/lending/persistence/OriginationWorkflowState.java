package io.github.finbridge.lending.persistence;

public enum OriginationWorkflowState {
    RISK_PENDING,
    RISK_RUNNING,
    RISK_COMPLETED,
    PAYMENT_PENDING,
    PAYMENT_SUBMITTING,
    PAYMENT_RECONCILIATION_REQUIRED,
    PAYMENT_FAILED,
    ORIGINATED
}
