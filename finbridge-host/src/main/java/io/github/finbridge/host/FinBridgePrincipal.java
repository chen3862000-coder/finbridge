package io.github.finbridge.host;

import java.util.Objects;
import java.util.UUID;

public record FinBridgePrincipal(String subject, UUID tenantId) {
    public FinBridgePrincipal {
        Objects.requireNonNull(subject, "subject");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
    }
}
