package io.github.finbridge.lending.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OriginationWorkflowRepository {
    OriginationWorkflow startRisk(UUID tenantId, UUID applicationId, Instant now);

    OriginationWorkflow resumeRisk(UUID tenantId, UUID applicationId, Instant now);

    java.util.List<WorkflowRecoveryCandidate> interruptedRiskWorkflows();

    record WorkflowRecoveryCandidate(UUID tenantId, UUID applicationId) { }

    Optional<OriginationWorkflow> findByApplication(UUID tenantId, UUID applicationId);

    java.util.List<WorkflowStepView> findSteps(UUID tenantId, UUID applicationId);

    record WorkflowStepView(UUID attemptId, String stepName, String state, int attemptNumber,
            Instant startedAt, Instant completedAt, UUID assessmentId, UUID paymentTransactionId,
            String failureCode) { }
}
