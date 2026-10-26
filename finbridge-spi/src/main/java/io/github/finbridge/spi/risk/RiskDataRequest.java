package io.github.finbridge.spi.risk;

import java.util.Objects;
import java.util.UUID;

public record RiskDataRequest(UUID assessmentId, UUID tenantId, String syntheticApplicantId) {
    public RiskDataRequest {
        Objects.requireNonNull(assessmentId, "assessmentId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(syntheticApplicantId, "syntheticApplicantId");
        if (syntheticApplicantId.isBlank()) {
            throw new IllegalArgumentException("syntheticApplicantId must not be blank");
        }
    }
}
