package io.github.finbridge.lending.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RiskAssessment(UUID assessmentId, UUID applicationId, UUID tenantId,
                             AssessmentStatus status, Integer creditScore, Boolean blacklistHit,
                             String fraudSignal, AssessmentDecision decision,
                             List<String> reasonCodes, List<ProviderExecutionReference> providers,
                             String policyVersion, Instant createdAt, Instant completedAt) {
    public RiskAssessment {
        Objects.requireNonNull(assessmentId, "assessmentId");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(status, "status");
        reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes, "reasonCodes"));
        providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(createdAt, "createdAt");
        if (status == AssessmentStatus.COMPLETED
                && (decision == null || completedAt == null || policyVersion.isBlank())) {
            throw new IllegalArgumentException("Completed assessment requires decision, policy and time");
        }
        if (completedAt != null && completedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("completedAt cannot precede createdAt");
        }
        if (creditScore != null && (creditScore < 0 || creditScore > 1000)) {
            throw new IllegalArgumentException("creditScore must be in [0, 1000]");
        }
    }

    public record ProviderExecutionReference(UUID executionId, String pluginId,
                                             String pluginVersion, String capability) {
        public ProviderExecutionReference {
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(pluginId, "pluginId");
            Objects.requireNonNull(pluginVersion, "pluginVersion");
            Objects.requireNonNull(capability, "capability");
        }
    }
}
