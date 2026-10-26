package io.github.finbridge.spi.risk;

public sealed interface RiskDataValue
        permits CreditScoreValue, BlacklistValue, FraudSignalValue {
    RiskDataType dataType();
}
