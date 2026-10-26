package io.github.finbridge.spi.risk;

public record CreditScoreValue(int score) implements RiskDataValue {
    @Override
    public RiskDataType dataType() {
        return RiskDataType.CREDIT_SCORE;
    }
}
