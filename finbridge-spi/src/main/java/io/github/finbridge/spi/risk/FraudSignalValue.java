package io.github.finbridge.spi.risk;

import java.util.Objects;

public record FraudSignalValue(Severity severity, String reasonCode) implements RiskDataValue {
    public enum Severity {
        NONE,
        LOW,
        ELEVATED,
        CRITICAL
    }

    public FraudSignalValue {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(reasonCode, "reasonCode");
    }

    @Override
    public RiskDataType dataType() {
        return RiskDataType.FRAUD_SIGNAL;
    }
}
