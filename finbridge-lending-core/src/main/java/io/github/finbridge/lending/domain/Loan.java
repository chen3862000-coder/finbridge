package io.github.finbridge.lending.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record Loan(UUID loanId, UUID applicationId, UUID tenantId, BigDecimal principal,
                   Currency currency, BigDecimal annualInterestRatePercent, int termMonths,
                   String productCode, int productVersion, LoanStatus status,
                   UUID disbursementTransactionId, Instant disbursedAt, Instant activatedAt,
                   Instant createdAt, long version) {
    public Loan {
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(annualInterestRatePercent, "annualInterestRatePercent");
        Objects.requireNonNull(productCode, "productCode");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (principal.signum() <= 0 || annualInterestRatePercent.signum() < 0 || termMonths <= 0
                || productVersion < 1 || version < 0 || productCode.isBlank()) {
            throw new IllegalArgumentException("Invalid loan values");
        }
        if (status == LoanStatus.ACTIVE && (disbursementTransactionId == null
                || disbursedAt == null || activatedAt == null)) {
            throw new IllegalArgumentException("Active loan requires confirmed disbursement fields");
        }
    }

    public Loan markDisbursementProcessing(UUID transactionId) {
        if (status != LoanStatus.PENDING_DISBURSEMENT) {
            throw new IllegalStateException("Loan is not awaiting disbursement");
        }
        return copy(LoanStatus.DISBURSEMENT_PROCESSING, transactionId, null, null);
    }

    public Loan activateAfterConfirmedDisbursement(Instant confirmedAt) {
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        if (status != LoanStatus.DISBURSEMENT_PROCESSING || disbursementTransactionId == null) {
            throw new IllegalStateException("Only a processing loan with payment intent can activate");
        }
        return copy(LoanStatus.ACTIVE, disbursementTransactionId, confirmedAt, confirmedAt);
    }

    public Loan recordPaymentIntent(UUID transactionId) {
        Objects.requireNonNull(transactionId, "transactionId");
        if (status != LoanStatus.PENDING_DISBURSEMENT) {
            throw new IllegalStateException("Loan is not awaiting its first disbursement intent");
        }
        return copy(LoanStatus.DISBURSEMENT_PROCESSING, transactionId, null, null);
    }

    public Loan recordDisbursementFailure() {
        if (status != LoanStatus.DISBURSEMENT_PROCESSING || disbursementTransactionId == null) {
            throw new IllegalStateException("Loan has no active disbursement intent");
        }
        return copy(LoanStatus.DISBURSEMENT_FAILED, disbursementTransactionId, null, null);
    }

    public Loan confirmDisbursement(UUID transactionId, Instant confirmedAt) {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        if (status == LoanStatus.ACTIVE && transactionId.equals(disbursementTransactionId)) {
            return this;
        }
        if (status != LoanStatus.DISBURSEMENT_PROCESSING
                || !transactionId.equals(disbursementTransactionId)) {
            throw new IllegalStateException("Confirmed payment does not own this loan");
        }
        return activateAfterConfirmedDisbursement(confirmedAt);
    }

    public Loan markDisbursementFailed() {
        if (status != LoanStatus.PENDING_DISBURSEMENT
                && status != LoanStatus.DISBURSEMENT_PROCESSING) {
            throw new IllegalStateException("Loan cannot transition to disbursement failure");
        }
        return copy(LoanStatus.DISBURSEMENT_FAILED, disbursementTransactionId, null, null);
    }

    private Loan copy(LoanStatus nextStatus, UUID transactionId, Instant disbursed,
                      Instant activated) {
        return new Loan(loanId, applicationId, tenantId, principal, currency,
                annualInterestRatePercent, termMonths, productCode, productVersion, nextStatus,
                transactionId, disbursed, activated, createdAt, version + 1);
    }
}
