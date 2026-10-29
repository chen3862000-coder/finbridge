package io.github.finbridge.host;

import java.util.Objects;
import java.util.UUID;

/** Trusted server-created identity context; never construct this record from caller-selected tenant data. */
public record FinBridgePrincipal(String subject, UUID tenantId) {
    public FinBridgePrincipal {
        Objects.requireNonNull(subject, "subject");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
    }
}
