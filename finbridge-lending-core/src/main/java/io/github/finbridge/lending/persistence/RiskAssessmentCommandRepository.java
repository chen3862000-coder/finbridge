package io.github.finbridge.lending.persistence;

import io.github.finbridge.lending.domain.AssessmentDecision;
import io.github.finbridge.lending.domain.RiskAssessment;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RiskAssessmentCommandRepository {
    RiskAssessment completeAndRecord(RiskAssessment assessment, AssessmentDecision decision,
                                     UUID attemptId, long expectedApplicationVersion,
                                     long expectedWorkflowVersion, Instant occurredAt);

    Optional<RiskAssessment> findById(UUID tenantId, UUID assessmentId);
}
