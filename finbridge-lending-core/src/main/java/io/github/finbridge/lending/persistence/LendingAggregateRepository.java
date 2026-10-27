package io.github.finbridge.lending.persistence;

import io.github.finbridge.lending.domain.Loan;
import io.github.finbridge.lending.domain.RepaymentPlan;
import io.github.finbridge.lending.domain.PaymentTransaction;
import io.github.finbridge.spi.PluginKey;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface LendingAggregateRepository extends OriginationWorkflowRepository {
    Optional<Loan> findLoanByApplication(UUID tenantId, UUID applicationId);

    Optional<Loan> findLoan(UUID tenantId, UUID loanId);

    Optional<RepaymentPlan> findRepaymentPlan(UUID tenantId, UUID loanId);

    Loan originate(Loan loan, RepaymentPlan plan);

    Loan originateApproved(Loan loan, RepaymentPlan plan);

    DisbursementClaim claimDisbursement(UUID tenantId, UUID loanId, PluginKey owner,
            UUID transactionId, String idempotencyKey, String requestFingerprint,
            UUID attemptId, Instant now);

    DisbursementClaim claimDisbursement(UUID tenantId, UUID loanId, PluginKey owner,
            String idempotencyKey, String requestFingerprint, UUID attemptId, Instant now);

    DisbursementClaim recordDisbursementOutcome(UUID tenantId, UUID paymentTransactionId,
            UUID expectedAttemptId, io.github.finbridge.spi.payment.PaymentStatus status,
            String providerTransactionId, String diagnosticCode, Instant confirmedAt,
            LocalDate disbursementDate, Instant now);

    DisbursementClaim markInterruptedDispatchUnknown(UUID tenantId, UUID paymentTransactionId,
            Instant now);

    Optional<PaymentTransaction> findPaymentTransaction(UUID tenantId, UUID paymentTransactionId);

    Optional<PaymentTransaction> findPaymentByLoan(UUID tenantId, UUID loanId);

    Optional<PaymentTransaction> findPaymentByIdempotencyKey(UUID tenantId, String idempotencyKey);

    Optional<DisbursementClaim> findDisbursement(UUID tenantId, UUID loanId);

    boolean hasUnresolvedPaymentOwner(PluginKey owner);

    record DisbursementClaim(PaymentTransaction transaction, Loan loan, RepaymentPlan plan,
                             OriginationWorkflow workflow, boolean dispatchClaimed) { }
}
