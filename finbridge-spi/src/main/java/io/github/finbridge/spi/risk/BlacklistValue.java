package io.github.finbridge.spi.risk;

public record BlacklistValue(boolean hit) implements RiskDataValue {
    @Override
    public RiskDataType dataType() {
        return RiskDataType.BLACKLIST;
    }
}
