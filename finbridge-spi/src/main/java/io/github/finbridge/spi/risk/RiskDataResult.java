package io.github.finbridge.spi.risk;

import java.util.Objects;

public record RiskDataResult(RiskDataType dataType, Status status,
                             RiskDataValue value, String reasonCode) {
    public enum Status {
        AVAILABLE,
        UNAVAILABLE,
        INVALID
    }

    public RiskDataResult {
        Objects.requireNonNull(dataType, "dataType");
        Objects.requireNonNull(status, "status");
        if (status == Status.AVAILABLE && value == null) {
            throw new IllegalArgumentException("Available risk data requires a typed value");
        }
        if (value != null && value.dataType() != dataType) {
            throw new IllegalArgumentException("Risk value type does not match result data type");
        }
        if (status != Status.AVAILABLE && value != null) {
            throw new IllegalArgumentException("Unavailable risk data must not contain a value");
        }
        if (status != Status.AVAILABLE && (reasonCode == null || reasonCode.isBlank())) {
            throw new IllegalArgumentException("Unavailable data requires a reason code");
        }
    }
}
