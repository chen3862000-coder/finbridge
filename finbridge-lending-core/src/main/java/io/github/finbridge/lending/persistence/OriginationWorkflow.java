package io.github.finbridge.lending.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OriginationWorkflow(UUID workflowId, UUID applicationId, UUID tenantId,
                                 OriginationWorkflowState state, UUID activeRiskAttemptId,
                                 UUID activeAssessmentId,
                                 UUID completedAssessmentId, long version,
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
        if (state == OriginationWorkflowState.RISK_COMPLETED && completedAssessmentId == null) {
            throw new IllegalArgumentException("Completed workflow requires assessment ID");
        }
    }
}
