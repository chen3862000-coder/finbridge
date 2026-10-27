package io.github.finbridge.lending.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OriginationWorkflow(UUID workflowId, UUID applicationId, UUID tenantId,
                                  OriginationWorkflowState state, UUID activeRiskAttemptId,
                                  UUID activeAssessmentId,
                                  UUID completedAssessmentId, UUID paymentTransactionId, long version,
                                  Instant createdAt, Instant updatedAt) {
    public OriginationWorkflow {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        if (state == OriginationWorkflowState.RISK_RUNNING
                && (activeRiskAttemptId == null || activeAssessmentId == null)) {
            throw new IllegalArgumentException("Running risk step requires attempt and assessment IDs");
        }
        if ((state == OriginationWorkflowState.RISK_COMPLETED
                || state == OriginationWorkflowState.PAYMENT_PENDING
                || state == OriginationWorkflowState.PAYMENT_SUBMITTING
                || state == OriginationWorkflowState.PAYMENT_RECONCILIATION_REQUIRED
                || state == OriginationWorkflowState.PAYMENT_FAILED
                || state == OriginationWorkflowState.ORIGINATED) && completedAssessmentId == null) {
            throw new IllegalArgumentException("Completed workflow requires assessment ID");
        }
        if ((state == OriginationWorkflowState.PAYMENT_SUBMITTING
                || state == OriginationWorkflowState.PAYMENT_RECONCILIATION_REQUIRED
                || state == OriginationWorkflowState.PAYMENT_FAILED
                || state == OriginationWorkflowState.ORIGINATED) && paymentTransactionId == null) {
            throw new IllegalArgumentException("Payment workflow requires transaction ID");
        }
    }

}
