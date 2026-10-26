package io.github.finbridge.spi;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OperationContext(UUID executionId, String correlationId, Instant deadline) {
    public OperationContext {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(deadline, "deadline");
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
    }
}
