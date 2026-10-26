package io.github.finbridge.lending.persistence;

import io.github.finbridge.lending.domain.RiskAssessment;
import java.util.Optional;
import java.util.UUID;

public interface RiskAssessmentRepository {
    RiskAssessment save(RiskAssessment assessment);

    Optional<RiskAssessment> findLatest(UUID tenantId, UUID applicationId);
}
