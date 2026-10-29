package io.github.finbridge.spi.risk;

import java.util.Objects;

/** Typed provider evidence with explicit availability and a reason for non-available results. */
public record RiskDataResult(RiskDataType dataType, Status status,
                             RiskDataValue value, String reasonCode) {
    /** Distinguishes usable evidence from provider unavailability and malformed evidence. */
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

    /** Creates an available result whose value type must match {@code dataType}. */
    public static RiskDataResult available(RiskDataValue value) {
        Objects.requireNonNull(value, "value");
        return new RiskDataResult(value.dataType(), Status.AVAILABLE, value, null);
    }

    /** Creates a result for a provider that could not supply the requested data. */
    public static RiskDataResult unavailable(RiskDataType dataType, String reasonCode) {
        return new RiskDataResult(dataType, Status.UNAVAILABLE, null, reasonCode);
    }

    /** Creates a result for evidence that was returned but failed validation. */
    public static RiskDataResult invalid(RiskDataType dataType, String reasonCode) {
        return new RiskDataResult(dataType, Status.INVALID, null, reasonCode);
    }
}
