package io.github.finbridge.spi.payment;

/** Provider view of a payment; UNKNOWN requires reconciliation and is not a failure signal. */
public enum PaymentStatus {
    /** Provider accepted the operation but has not reached a terminal outcome. */
    PROCESSING,
    /** Provider confirmed completion and must supply a transaction reference. */
    SUCCEEDED,
    /** Provider confirmed that the operation failed definitively. */
    FAILED,
    /** Outcome is uncertain; callers must query the original owner, not resubmit or fail over. */
    UNKNOWN
}
