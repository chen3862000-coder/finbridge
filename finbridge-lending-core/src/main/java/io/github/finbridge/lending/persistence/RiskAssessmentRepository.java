package io.github.finbridge.lending.persistence;

import io.github.finbridge.lending.domain.RiskAssessment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiskAssessmentRepository extends RiskAssessmentCommandRepository {
    RiskAssessment save(RiskAssessment assessment);

    RiskAssessmentClaim claimAssessment(UUID tenantId, UUID applicationId, UUID assessmentId,
            UUID workflowId, UUID attemptId, long workflowVersion,
            List<RiskAssessment.ProviderExecutionReference> providers, Instant now);

    Optional<RiskAssessment> findLatest(UUID tenantId, UUID applicationId);

    @Override
    Optional<RiskAssessment> findById(UUID tenantId, UUID assessmentId);

    record RiskAssessmentClaim(RiskAssessment assessment, boolean completed) { }
}
