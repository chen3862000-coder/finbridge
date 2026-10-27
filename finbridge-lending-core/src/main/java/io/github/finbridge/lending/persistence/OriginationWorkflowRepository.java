package io.github.finbridge.lending.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OriginationWorkflowRepository {
    OriginationWorkflow startRisk(UUID tenantId, UUID applicationId, Instant now);

    OriginationWorkflow resumeRisk(UUID tenantId, UUID applicationId, Instant now);

    Optional<OriginationWorkflow> findByApplication(UUID tenantId, UUID applicationId);
}
