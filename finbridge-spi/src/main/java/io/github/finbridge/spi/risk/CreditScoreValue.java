package io.github.finbridge.spi.risk;

public record CreditScoreValue(int score) implements RiskDataValue {
    public CreditScoreValue {
        if (score < 0 || score > 1000) {
            throw new IllegalArgumentException("score must be in [0, 1000]");
        }
    }

    @Override
    public RiskDataType dataType() {
        return RiskDataType.CREDIT_SCORE;
    }
}
