package io.github.finbridge.lending.application;

public final class LendingCoreException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LendingCoreException(String message) {
        super(message);
    }

    public LendingCoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
