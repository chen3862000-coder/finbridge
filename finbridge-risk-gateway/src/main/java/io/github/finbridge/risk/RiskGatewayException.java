package io.github.finbridge.risk;

public final class RiskGatewayException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RiskGatewayException(String message) {
        super(message);
    }
}
