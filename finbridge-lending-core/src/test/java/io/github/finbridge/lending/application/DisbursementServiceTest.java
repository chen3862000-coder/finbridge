package io.github.finbridge.lending.application;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.finbridge.lending.domain.Loan;
import io.github.finbridge.lending.domain.LoanStatus;
import io.github.finbridge.lending.domain.PaymentTransaction;
import io.github.finbridge.lending.domain.PaymentTransactionStatus;
import io.github.finbridge.lending.domain.RepaymentPlan;
import io.github.finbridge.lending.persistence.LendingAggregateRepository;
import io.github.finbridge.lending.persistence.LendingAggregateRepository.DisbursementClaim;
import io.github.finbridge.lending.persistence.OriginationWorkflow;
import io.github.finbridge.lending.persistence.OriginationWorkflowState;
import io.github.finbridge.payment.PaymentGateway;
import io.github.finbridge.runtime.PluginBinding;
import io.github.finbridge.runtime.PluginRuntime;
import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.spi.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DisbursementServiceTest {
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    private static final PluginKey OWNER = new PluginKey("alphapay", "1.0.0");
    private final LendingAggregateRepository aggregates = mock(LendingAggregateRepository.class);
    private final LoanOriginationService origination = mock(LoanOriginationService.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final PluginRuntime runtime = mock(PluginRuntime.class);
    private final UUID tenantId = UUID.randomUUID();
    private final UUID loanId = UUID.randomUUID();
    private final UUID transactionId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();
    private final String key = "payment-idempotency-key";
    private final String correlation = "synthetic-correlation";
    private final Loan loan = new Loan(loanId, UUID.randomUUID(), tenantId,
            new BigDecimal("1000.00"), Currency.getInstance("CNY"),
            new BigDecimal("12.000000"), 3, "CASH_LOAN_DEMO", 1,
            LoanStatus.PENDING_DISBURSEMENT, null, null, null, NOW, 0);
    private final DisbursementService service = new DisbursementService(aggregates, origination,
            gateway, runtime, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void bindSyntheticPaymentOwner() {
        when(runtime.binding(tenantId, Capability.DISBURSE))
                .thenReturn(new PluginBinding(tenantId, Capability.DISBURSE, OWNER, 1));
        when(origination.getLoan(tenantId, loanId)).thenReturn(loan);
    }

    @Test
    void dispatchPersistsProviderResultAgainstDurableClaim() {
        DisbursementClaim claim = claim(PaymentTransactionStatus.SUBMISSION_IN_PROGRESS, true,
                OriginationWorkflowState.PAYMENT_SUBMITTING);
        when(aggregates.findPaymentByIdempotencyKey(tenantId, key)).thenReturn(Optional.empty());
        when(aggregates.claimDisbursement(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(claim);
        PaymentGateway.DisbursementOutcome outcome = outcome(PaymentStatus.SUCCEEDED, "provider-ref", null);
        when(gateway.disburse(any(), any(), any(), any())).thenReturn(outcome);
        when(aggregates.recordDisbursementOutcome(tenantId, transactionId, attemptId,
                PaymentStatus.SUCCEEDED, "provider-ref", null, NOW, LocalDate.of(2026, 3, 1), NOW))
                .thenReturn(claim);

        assertSame(claim, service.dispatch(tenantId, loanId, key, correlation));

        verify(gateway).disburse(any(), org.mockito.ArgumentMatchers.eq(OWNER), any(), any());
        verify(aggregates).recordDisbursementOutcome(tenantId, transactionId, attemptId,
                PaymentStatus.SUCCEEDED, "provider-ref", null, NOW, LocalDate.of(2026, 3, 1), NOW);
    }

    @Test
    void matchingReplayDoesNotInvokeProviderAgain() {
        DisbursementClaim replay = claim(PaymentTransactionStatus.UNKNOWN, false,
                OriginationWorkflowState.PAYMENT_RECONCILIATION_REQUIRED);
        when(aggregates.findPaymentByIdempotencyKey(tenantId, key))
                .thenReturn(Optional.of(replay.transaction()));
        when(aggregates.claimDisbursement(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(replay);

        assertSame(replay, service.dispatch(tenantId, loanId, key, correlation));

        verify(gateway, never()).disburse(any(), any(), any(), any());
    }

    @Test
    void dispatchValidatesRequestBeforeLoadingLoan() {
        assertThrows(IllegalArgumentException.class,
                () -> service.dispatch(tenantId, loanId, " ", correlation));
        assertThrows(IllegalArgumentException.class,
                () -> service.dispatch(tenantId, loanId, key, " "));
        assertThrows(NullPointerException.class,
                () -> service.dispatch(null, loanId, key, correlation));
        verify(origination, never()).getLoan(any(), any());
    }

    @Test
    void dispatchRejectsIdempotencyRequestConflict() {
        DisbursementClaim existing = claim(PaymentTransactionStatus.UNKNOWN, false,
                OriginationWorkflowState.PAYMENT_RECONCILIATION_REQUIRED);
        when(aggregates.findPaymentByIdempotencyKey(tenantId, key))
                .thenReturn(Optional.of(existing.transaction()));
        when(aggregates.claimDisbursement(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new LendingCoreException("Payment idempotency key conflicts"));

        assertThrows(LendingCoreException.class,
                () -> service.dispatch(tenantId, loanId, key, correlation));
    }

    @Test
    void reconcileRestoresAlreadyPersistedSuccess() {
        DisbursementClaim succeeded = claim(PaymentTransactionStatus.SUCCEEDED, false,
                OriginationWorkflowState.ORIGINATED);
        when(origination.getDisbursement(tenantId, loanId)).thenReturn(succeeded);
        when(aggregates.recordDisbursementOutcome(tenantId, transactionId, attemptId,
                PaymentStatus.SUCCEEDED, "provider-ref", null, NOW, LocalDate.of(2026, 3, 1), NOW))
                .thenReturn(succeeded);

        assertSame(succeeded, service.reconcile(tenantId, loanId, correlation));

        verify(gateway, never()).reconcile(any(), any(), any(), any(), any(), any());
    }

    @Test
    void alreadyFinalizedFailureReturnsWithoutProviderCall() {
        DisbursementClaim failed = claim(PaymentTransactionStatus.FAILED, false,
                OriginationWorkflowState.PAYMENT_FAILED);
        when(origination.getDisbursement(tenantId, loanId)).thenReturn(failed);

        assertSame(failed, service.reconcile(tenantId, loanId, correlation));

        verify(aggregates, never()).recordDisbursementOutcome(any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void failedPaymentCompletesWorkflowWhenLocalFinalizationIsPending() {
        DisbursementClaim failed = claim(PaymentTransactionStatus.FAILED, false,
                OriginationWorkflowState.PAYMENT_RECONCILIATION_REQUIRED);
        when(origination.getDisbursement(tenantId, loanId)).thenReturn(failed);
        when(aggregates.recordDisbursementOutcome(tenantId, transactionId, attemptId,
                PaymentStatus.FAILED, null, "DECLINED", null, null, NOW)).thenReturn(failed);

        assertSame(failed, service.reconcile(tenantId, loanId, correlation));
    }

    @Test
    void interruptedSubmissionIsMarkedUnknownBeforeProviderReconciliation() {
        DisbursementClaim submitting = claim(PaymentTransactionStatus.SUBMISSION_IN_PROGRESS, false,
                OriginationWorkflowState.PAYMENT_SUBMITTING);
        DisbursementClaim unknown = claim(PaymentTransactionStatus.UNKNOWN, false,
                OriginationWorkflowState.PAYMENT_RECONCILIATION_REQUIRED);
        when(origination.getDisbursement(tenantId, loanId)).thenReturn(submitting);
        when(aggregates.markInterruptedDispatchUnknown(tenantId, transactionId, NOW)).thenReturn(unknown);
        when(gateway.reconcile(org.mockito.ArgumentMatchers.eq(runtime),
                org.mockito.ArgumentMatchers.eq(OWNER), org.mockito.ArgumentMatchers.eq(transactionId),
                org.mockito.ArgumentMatchers.eq(key), org.mockito.ArgumentMatchers.isNull(),
                any(OperationContext.class)))
                .thenReturn(outcome(PaymentStatus.UNKNOWN, null, "NOT_FOUND"));
        when(aggregates.recordDisbursementOutcome(tenantId, transactionId, attemptId,
                PaymentStatus.UNKNOWN, null, "NOT_FOUND", null, null, NOW)).thenReturn(unknown);

        assertSame(unknown, service.reconcile(tenantId, loanId, correlation));

        verify(aggregates).markInterruptedDispatchUnknown(tenantId, transactionId, NOW);
    }

    @Test
    void unresolvedPaymentUsesStoredOwnerAndTransactionReference() {
        DisbursementClaim unknown = claim(PaymentTransactionStatus.UNKNOWN, false,
                OriginationWorkflowState.PAYMENT_RECONCILIATION_REQUIRED);
        when(origination.getDisbursement(tenantId, loanId)).thenReturn(unknown);
        when(gateway.reconcile(org.mockito.ArgumentMatchers.eq(runtime),
                org.mockito.ArgumentMatchers.eq(OWNER), org.mockito.ArgumentMatchers.eq(transactionId),
                org.mockito.ArgumentMatchers.eq(key), org.mockito.ArgumentMatchers.isNull(),
                any(OperationContext.class)))
                .thenReturn(outcome(PaymentStatus.PROCESSING, null, null));
        when(aggregates.recordDisbursementOutcome(tenantId, transactionId, attemptId,
                PaymentStatus.PROCESSING, null, null, null, null, NOW)).thenReturn(unknown);

        assertSame(unknown, service.reconcile(tenantId, loanId, correlation));
    }

    @Test
    void reconciliationValidatesInputs() {
        assertThrows(NullPointerException.class,
                () -> service.reconcile(null, loanId, correlation));
        assertThrows(NullPointerException.class,
                () -> service.reconcile(tenantId, null, correlation));
        assertThrows(IllegalArgumentException.class,
                () -> service.reconcile(tenantId, loanId, " "));
    }

    @Test
    void missingProviderBindingFailsClosed() {
        when(runtime.binding(tenantId, Capability.DISBURSE))
                .thenThrow(new IllegalStateException("No binding"));
        when(aggregates.findPaymentByIdempotencyKey(tenantId, key)).thenReturn(Optional.empty());

        assertThrows(LendingCoreException.class,
                () -> service.dispatch(tenantId, loanId, key, correlation));
    }

    @Test
    void blankCorrelationIsRejectedForStatusAndReconciliation() {
        assertThrows(IllegalArgumentException.class,
                () -> service.reconcile(tenantId, loanId, " "));
        verify(origination, never()).getDisbursement(any(), any());
    }

    @Test
    void fingerprintRejectsUnavailableDigestAlgorithm() {
        assertThrows(IllegalStateException.class, () -> DisbursementService.fingerprint(
                tenantId, loanId, "1000.00", "CNY", "unavailable-test-algorithm"));
    }

    @Test
    void statusDelegatesToOriginationLookup() {
        DisbursementClaim expected = claim(PaymentTransactionStatus.UNKNOWN, false,
                OriginationWorkflowState.PAYMENT_RECONCILIATION_REQUIRED);
        when(origination.getDisbursement(tenantId, loanId)).thenReturn(expected);

        assertSame(expected, service.getStatus(tenantId, loanId));
    }

    @Test
    void startupRecoveryMarksPaymentOwnerUnavailableWhenOriginalProviderCannotBeLoaded() {
        PaymentTransaction unresolved = transaction(PaymentTransactionStatus.UNKNOWN);
        when(aggregates.findUnresolvedPaymentTransactions()).thenReturn(java.util.List.of(unresolved));
        when(origination.getDisbursement(tenantId, loanId))
                .thenThrow(new LendingCoreException("Payment owner is unavailable"));

        assertEquals(1, service.recoverUnresolvedPayments());

        verify(aggregates).markPaymentOwnerUnavailable(tenantId, transactionId, attemptId, NOW);
        verify(gateway, never()).reconcile(any(), any(), any(), any(), any(), any());
    }

    @Test
    void dispatchPersistsReconciliationRequiredWhenProviderOwnerIsUnavailable() {
        DisbursementClaim dispatch = claim(PaymentTransactionStatus.SUBMISSION_IN_PROGRESS, true,
                OriginationWorkflowState.PAYMENT_SUBMITTING);
        DisbursementClaim unavailable = claim(PaymentTransactionStatus.RECONCILIATION_REQUIRED, false,
                OriginationWorkflowState.PAYMENT_RECONCILIATION_REQUIRED);
        when(aggregates.findPaymentByIdempotencyKey(tenantId, key)).thenReturn(Optional.empty());
        when(aggregates.claimDisbursement(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(dispatch);
        when(gateway.disburse(any(), any(), any(), any()))
                .thenReturn(outcome(PaymentStatus.UNKNOWN, null, "PAYMENT_OWNER_UNAVAILABLE"));
        when(origination.getDisbursement(tenantId, loanId)).thenReturn(unavailable);

        assertSame(unavailable, service.dispatch(tenantId, loanId, key, correlation));

        verify(aggregates).markPaymentOwnerUnavailable(tenantId, transactionId, attemptId, NOW);
    }

    private DisbursementClaim claim(PaymentTransactionStatus status, boolean dispatchClaimed,
            OriginationWorkflowState state) {
        UUID assessmentId = UUID.randomUUID();
        PaymentTransaction transaction = transaction(status);
        OriginationWorkflow workflow = new OriginationWorkflow(UUID.randomUUID(), loan.applicationId(),
                tenantId, state, null, null, assessmentId, transactionId, 1, NOW, NOW);
        return new DisbursementClaim(transaction, loan, mock(RepaymentPlan.class), workflow,
                dispatchClaimed);
    }

    private PaymentTransaction transaction(PaymentTransactionStatus status) {
        return new PaymentTransaction(transactionId, tenantId, loanId, "DISBURSE", key,
                "f".repeat(64), loan.principal(), loan.currency(), OWNER,
                status == PaymentTransactionStatus.SUCCEEDED ? "provider-ref" : null,
                transactionId.toString(), status, attemptId, NOW, NOW, 1,
                status == PaymentTransactionStatus.FAILED ? "DECLINED" : null);
    }

    private PaymentGateway.DisbursementOutcome outcome(PaymentStatus status, String providerRef,
            String diagnostic) {
        return new PaymentGateway.DisbursementOutcome(OWNER, status, providerRef, diagnostic);
    }
}
