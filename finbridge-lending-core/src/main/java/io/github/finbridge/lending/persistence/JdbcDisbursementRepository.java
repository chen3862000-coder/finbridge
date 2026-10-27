package io.github.finbridge.lending.persistence;

import io.github.finbridge.lending.application.LendingCoreException;
import io.github.finbridge.lending.persistence.LendingAggregateRepository.DisbursementClaim;
import io.github.finbridge.lending.domain.Loan;
import io.github.finbridge.lending.domain.LoanStatus;
import io.github.finbridge.lending.domain.PaymentTransaction;
import io.github.finbridge.lending.domain.PaymentTransactionStatus;
import io.github.finbridge.lending.domain.RepaymentInstallment;
import io.github.finbridge.lending.domain.RepaymentPlan;
import io.github.finbridge.lending.domain.RepaymentPlanStatus;
import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.spi.payment.PaymentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

final class JdbcDisbursementRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JdbcLendingAggregateRepository lending;

    JdbcDisbursementRepository(JdbcTemplate jdbc, TransactionTemplate transactions,
            JdbcLendingAggregateRepository lending) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.lending = lending;
    }

    DisbursementClaim claimDisbursement(UUID tenantId, UUID loanId, PluginKey owner,
            UUID transactionId, String idempotencyKey, String fingerprint,
            UUID attemptId, java.time.Instant now) {
        return Objects.requireNonNull(transactions.execute(new TransactionCallback<>() {
            @Override
            public DisbursementClaim doInTransaction(TransactionStatus status) {
                return claimTransaction(tenantId, loanId, owner, transactionId,
                        idempotencyKey, fingerprint, attemptId, now);
            }
        }),
                "Disbursement claim result");
    }

    private DisbursementClaim claimTransaction(UUID tenantId, UUID loanId, PluginKey owner,
            UUID transactionId, String key, String fingerprint, UUID attemptId, java.time.Instant now) {
        Loan loan = lending.findLoanForUpdate(tenantId, loanId)
                .orElse(null);
        loan = requirePresent(Optional.ofNullable(loan), "Loan was not found");
        RepaymentPlan plan = lending.findRepaymentPlan(tenantId, loanId)
                .orElse(null);
        plan = requirePresent(Optional.ofNullable(plan), "Repayment plan was not found");
        PaymentTransaction keyed = findPaymentByIdempotencyKeyForUpdate(tenantId, key).orElse(null);
        if (keyed != null) {
            return replayKeyedPayment(keyed, loan, plan, loanId, key, fingerprint, owner, tenantId);
        }
        OriginationWorkflow workflow = lending.findWorkflowForUpdate(tenantId, loan.applicationId());
        validateDispatchState(loan, workflow);
        Optional<PaymentTransaction> existing = findPaymentByLoan(tenantId, loanId);
        if (existing.isPresent()) {
            verifySamePayment(existing.orElseThrow(), key, fingerprint, owner);
            throw new LendingCoreException("A previous dispatch exists and cannot be resubmitted");
        }
        if (loan.status() != LoanStatus.PENDING_DISBURSEMENT) {
            throw new LendingCoreException("A previous dispatch exists and cannot be resubmitted");
        }
        return createDispatchClaim(new DispatchClaimRequest(tenantId, loan, plan, workflow,
                owner, transactionId, key, fingerprint, attemptId, now));
    }

    private DisbursementClaim replayKeyedPayment(PaymentTransaction transaction, Loan loan,
            RepaymentPlan plan, UUID loanId, String key, String fingerprint, PluginKey owner,
            UUID tenantId) {
        verifySamePayment(transaction, key, fingerprint, owner);
        if (!transaction.loanId().equals(loanId)) {
            throw new LendingCoreException("Payment idempotency key is owned by a different loan");
        }
        OriginationWorkflow workflow = workflowsForPayment(tenantId, transaction.paymentTransactionId());
        if (transaction.status() == PaymentTransactionStatus.SUBMISSION_IN_PROGRESS) {
            PaymentTransaction unresolved = updatePaymentOutcome(transaction, PaymentTransactionStatus.UNKNOWN, null,
                    "DISPATCH_ALREADY_CLAIMED", transaction.updatedAt());
            workflow = lending.updateWorkflowPayment(workflow, unresolved.paymentTransactionId(),
                    OriginationWorkflowState.PAYMENT_RECONCILIATION_REQUIRED, unresolved.updatedAt());
            markPaymentStepReconciliation(unresolved.dispatchAttemptId(), "DISPATCH_ALREADY_CLAIMED");
            return new DisbursementClaim(unresolved, loan, plan, workflow, false);
        }
        return new DisbursementClaim(transaction, loan, plan, workflow, false);
    }

    private void validateDispatchState(Loan loan, OriginationWorkflow workflow) {
        if (workflow == null || workflow.state() != OriginationWorkflowState.PAYMENT_PENDING) {
            throw new LendingCoreException("Origination workflow is not ready for disbursement");
        }
        if (loan.status() != LoanStatus.PENDING_DISBURSEMENT
                && loan.status() != LoanStatus.DISBURSEMENT_PROCESSING) {
            throw new LendingCoreException("Loan is not eligible for disbursement dispatch");
        }
    }

    private DisbursementClaim createDispatchClaim(DispatchClaimRequest request) {
        PaymentTransaction transaction = new PaymentTransaction(request.transactionId(), request.tenantId(),
                request.loan().loanId(), "DISBURSE", request.key(), request.fingerprint(),
                request.loan().principal(), request.loan().currency(), request.owner(),
                null, request.transactionId().toString(), PaymentTransactionStatus.SUBMISSION_IN_PROGRESS,
                request.attemptId(), request.now(), request.now(), 0, null);
        insertPayment(transaction);
        Loan processing = request.loan().recordPaymentIntent(request.transactionId());
        lending.updateLoan(processing, request.loan().version());
        OriginationWorkflow submitting = lending.updateWorkflowPayment(request.workflow(),
                request.transactionId(), OriginationWorkflowState.PAYMENT_SUBMITTING, request.now());
        insertPaymentStep(request.workflow().workflowId(), request.attemptId(),
                request.transactionId(), request.now());
        return new DisbursementClaim(transaction, processing, request.plan(), submitting, true);
    }

    private record DispatchClaimRequest(UUID tenantId, Loan loan, RepaymentPlan plan,
            OriginationWorkflow workflow, PluginKey owner, UUID transactionId, String key,
            String fingerprint, UUID attemptId, java.time.Instant now) { }

    DisbursementClaim recordOutcome(UUID tenantId, UUID transactionId, UUID expectedAttemptId,
            PaymentStatus status, String providerTransactionId, String diagnosticCode,
            java.time.Instant confirmedAt, java.time.LocalDate disbursementDate, java.time.Instant now) {
        PaymentTransaction recorded = transactions.execute(new TransactionCallback<>() {
            @Override
            public PaymentTransaction doInTransaction(TransactionStatus transactionStatus) {
                return recordOutcomeTransaction(tenantId, transactionId, expectedAttemptId,
                        status, providerTransactionId, diagnosticCode, now);
            }
        });
        Objects.requireNonNull(recorded, "Recorded payment result");
        return finalizeRecordedOutcome(tenantId, transactionId, recorded, diagnosticCode,
                confirmedAt, disbursementDate, now);
    }

    private PaymentTransaction recordOutcomeTransaction(UUID tenantId, UUID transactionId,
            UUID expectedAttemptId, PaymentStatus status, String providerTransactionId,
            String diagnosticCode, java.time.Instant now) {
        PaymentTransaction transaction = findPaymentForUpdate(tenantId, transactionId)
                .orElse(null);
        transaction = requirePresent(Optional.ofNullable(transaction), "Payment transaction was not found");
        if (!transaction.dispatchAttemptId().equals(expectedAttemptId)) {
            throw new LendingCoreException("Payment dispatch attempt was superseded");
        }
        if (isTerminal(transaction.status())) {
            return transaction;
        }
        if (isAmbiguousSubmission(transaction.status(), status)) {
            return updatePaymentOutcome(transaction, PaymentTransactionStatus.UNKNOWN, null,
                    diagnosticCode, now);
        }
        if (transaction.status().unresolved() && status == PaymentStatus.UNKNOWN) {
            return updatePaymentOutcome(transaction, PaymentTransactionStatus.RECONCILIATION_REQUIRED,
                    null, diagnosticCode, now);
        }
        return updatePaymentOutcome(transaction, paymentStatus(status), providerTransactionId,
                diagnosticCode, now);
    }

    private boolean isTerminal(PaymentTransactionStatus status) {
        return status == PaymentTransactionStatus.SUCCEEDED || status == PaymentTransactionStatus.FAILED;
    }

    private boolean isAmbiguousSubmission(PaymentTransactionStatus transactionStatus, PaymentStatus status) {
        return transactionStatus == PaymentTransactionStatus.SUBMISSION_IN_PROGRESS
                && (status == PaymentStatus.UNKNOWN || status == PaymentStatus.PROCESSING);
    }

    private DisbursementClaim finalizeRecordedOutcome(UUID tenantId, UUID transactionId,
            PaymentTransaction transaction, String diagnostic, java.time.Instant confirmedAt,
            java.time.LocalDate date, java.time.Instant now) {
        return switch (transaction.status()) {
            case SUCCEEDED -> finalizeSuccess(tenantId, transactionId, confirmedAt, date, now);
            case FAILED -> finalizeFailure(tenantId, transactionId, now);
            case UNKNOWN, PROCESSING, SUBMISSION_IN_PROGRESS, RECONCILIATION_REQUIRED -> reconcile(tenantId, transactionId,
                    diagnostic, now);
        };
    }

    DisbursementClaim markInterrupted(UUID tenantId, UUID transactionId, java.time.Instant now) {
        PaymentTransaction transaction = transactions.execute(new TransactionCallback<>() {
            @Override
            public PaymentTransaction doInTransaction(TransactionStatus status) {
                return markInterruptedTransaction(tenantId, transactionId, now);
            }
        });
        Objects.requireNonNull(transaction, "Interrupted payment recovery result");
        return reconcile(tenantId, transactionId, "HOST_RESTART_DURING_DISPATCH", now);
    }

    private PaymentTransaction markInterruptedTransaction(UUID tenantId, UUID transactionId,
            java.time.Instant now) {
        PaymentTransaction current = findPaymentForUpdate(tenantId, transactionId)
                .orElse(null);
        current = requirePresent(Optional.ofNullable(current), "Payment transaction was not found");
        if (current.status() != PaymentTransactionStatus.SUBMISSION_IN_PROGRESS) {
            return current;
        }
        return updatePaymentOutcome(current, PaymentTransactionStatus.UNKNOWN, null,
                "HOST_RESTART_DURING_DISPATCH", now);
    }

    private DisbursementClaim finalizeSuccess(UUID tenantId, UUID transactionId,
            java.time.Instant confirmedAt, java.time.LocalDate date, java.time.Instant now) {
        if (confirmedAt == null || date == null) {
            throw new LendingCoreException("Successful payment requires confirmation time and date");
        }
        return Objects.requireNonNull(transactions.execute(new TransactionCallback<>() {
            @Override
            public DisbursementClaim doInTransaction(TransactionStatus status) {
                return finalizeSuccessTransaction(tenantId, transactionId, confirmedAt, date, now);
            }
        }), "Successful disbursement finalization result");
    }

    private DisbursementClaim finalizeSuccessTransaction(UUID tenantId, UUID transactionId,
            java.time.Instant confirmedAt, java.time.LocalDate date, java.time.Instant now) {
            PaymentTransaction transaction = findPaymentForUpdate(tenantId, transactionId)
                    .orElse(null);
            transaction = requirePresent(Optional.ofNullable(transaction), "Payment transaction was not found");
            if (transaction.status() != PaymentTransactionStatus.SUCCEEDED) {
                throw new LendingCoreException("Only persisted payment success can activate a loan");
            }
            OriginationWorkflow workflow = workflowsForPayment(tenantId, transactionId);
            Loan loan = lending.findLoanForUpdate(tenantId, transaction.loanId())
                    .orElse(null);
            loan = requirePresent(Optional.ofNullable(loan), "Payment loan was not found");
            RepaymentPlan plan = lending.findRepaymentPlan(tenantId, loan.loanId())
                    .orElse(null);
            plan = requirePresent(Optional.ofNullable(plan), "Payment repayment plan was not found");
            if (loan.status() != LoanStatus.ACTIVE) {
                activateLoanAndPlan(loan, plan, transactionId, confirmedAt, date);
                updateApplicationOriginated(loan, confirmedAt);
            }
            if (workflow.state() != OriginationWorkflowState.ORIGINATED) {
                workflow = lending.updateWorkflowPayment(workflow, transactionId,
                        OriginationWorkflowState.ORIGINATED, now);
                completePaymentStep(transaction.dispatchAttemptId(), now);
            }
            Loan persistedLoan = requirePresent(lending.findLoan(tenantId, loan.loanId()),
                    "Payment loan was not found");
            RepaymentPlan persistedPlan = requirePresent(lending.findRepaymentPlan(tenantId, loan.loanId()),
                    "Payment repayment plan was not found");
            return new DisbursementClaim(transaction, persistedLoan, persistedPlan, workflow, false);
    }

    private DisbursementClaim finalizeFailure(UUID tenantId, UUID transactionId, java.time.Instant now) {
        return Objects.requireNonNull(transactions.execute(new TransactionCallback<>() {
            @Override
            public DisbursementClaim doInTransaction(TransactionStatus status) {
                return finalizeFailureTransaction(tenantId, transactionId, now);
            }
        }), "Failed disbursement finalization result");
    }

    private DisbursementClaim finalizeFailureTransaction(UUID tenantId, UUID transactionId,
            java.time.Instant now) {
            PaymentTransaction transaction = findPaymentForUpdate(tenantId, transactionId)
                    .orElse(null);
            transaction = requirePresent(Optional.ofNullable(transaction), "Payment transaction was not found");
            OriginationWorkflow workflow = workflowsForPayment(tenantId, transactionId);
            Loan loan = lending.findLoanForUpdate(tenantId, transaction.loanId())
                    .orElse(null);
            loan = requirePresent(Optional.ofNullable(loan), "Payment loan was not found");
            RepaymentPlan plan = lending.findRepaymentPlan(tenantId, loan.loanId())
                    .orElse(null);
            plan = requirePresent(Optional.ofNullable(plan), "Payment repayment plan was not found");
            if (workflow.state() == OriginationWorkflowState.PAYMENT_FAILED) {
                return new DisbursementClaim(transaction, loan, plan, workflow, false);
            }
            if (loan.status() != LoanStatus.DISBURSEMENT_FAILED) {
                lending.updateLoan(loan.recordDisbursementFailure(), loan.version());
            }
            workflow = lending.updateWorkflowPayment(workflow, transactionId,
                    OriginationWorkflowState.PAYMENT_FAILED, now);
            closePaymentStep(transaction.dispatchAttemptId(), now, transaction.diagnosticCode());
            Loan persistedLoan = requirePresent(lending.findLoan(tenantId, loan.loanId()),
                    "Payment loan was not found");
            return new DisbursementClaim(transaction, persistedLoan, plan, workflow, false);
    }

    private DisbursementClaim reconcile(UUID tenantId, UUID transactionId,
            String diagnostic, java.time.Instant now) {
        return Objects.requireNonNull(transactions.execute(new TransactionCallback<>() {
            @Override
            public DisbursementClaim doInTransaction(TransactionStatus status) {
                return reconcileTransaction(tenantId, transactionId, diagnostic, now);
            }
        }), "Unresolved disbursement result");
    }

    private DisbursementClaim reconcileTransaction(UUID tenantId, UUID transactionId,
            String diagnostic, java.time.Instant now) {
            PaymentTransaction transaction = findPaymentForUpdate(tenantId, transactionId)
                    .orElse(null);
            transaction = requirePresent(Optional.ofNullable(transaction), "Payment transaction was not found");
            OriginationWorkflow workflow = workflowsForPayment(tenantId, transactionId);
            if (workflow.state() != OriginationWorkflowState.PAYMENT_RECONCILIATION_REQUIRED) {
                workflow = lending.updateWorkflowPayment(workflow, transactionId,
                        OriginationWorkflowState.PAYMENT_RECONCILIATION_REQUIRED, now);
                markPaymentStepReconciliation(transaction.dispatchAttemptId(), diagnostic);
            }
            Loan loan = lending.findLoan(tenantId, transaction.loanId())
                    .orElse(null);
            loan = requirePresent(Optional.ofNullable(loan), "Payment loan was not found");
            RepaymentPlan plan = lending.findRepaymentPlan(tenantId, loan.loanId())
                    .orElse(null);
            plan = requirePresent(Optional.ofNullable(plan), "Payment repayment plan was not found");
            return new DisbursementClaim(transaction, loan, plan, workflow, false);
    }

    private void activateLoanAndPlan(Loan loan, RepaymentPlan plan, UUID transactionId,
            java.time.Instant confirmedAt, java.time.LocalDate date) {
        Loan activated = loan.confirmDisbursement(transactionId, confirmedAt);
        RepaymentPlan activatedPlan = plan.activate(confirmedAt, date);
        lending.updateLoan(activated, loan.version());
        int updated = jdbc.update("UPDATE repayment_plans SET status = ?, activated_at = ? "
                        + "WHERE tenant_id = ? AND plan_id = ? AND status = 'PENDING_ACTIVATION'",
                RepaymentPlanStatus.ACTIVE.name(), Timestamp.from(confirmedAt), plan.tenantId(), plan.planId());
        requireOne(updated, "Repayment plan activation was superseded");
        for (RepaymentInstallment installment : activatedPlan.installments()) {
            jdbc.update("UPDATE repayment_installments SET due_date = ? "
                            + "WHERE installment_id = ? AND plan_id = ? AND status = ?",
                    installment.dueDate(), installment.installmentId(), installment.planId(),
                    installment.status().name());
        }
    }

    private void updateApplicationOriginated(Loan loan, java.time.Instant now) {
        int updated = jdbc.update("UPDATE loan_applications SET status = 'ORIGINATED', updated_at = ?, "
                        + "version = version + 1 WHERE tenant_id = ? AND application_id = ? "
                        + "AND status = 'ORIGINATION_IN_PROGRESS'",
                Timestamp.from(now), loan.tenantId(), loan.applicationId());
        requireOne(updated, "Application could not be marked originated");
    }

    private OriginationWorkflow workflowsForPayment(UUID tenantId, UUID transactionId) {
        Optional<OriginationWorkflow> workflow = jdbc.query("SELECT * FROM origination_workflows WHERE tenant_id = ? "
                        + "AND payment_transaction_id = ? FOR UPDATE",
                lending::mapWorkflow, tenantId, transactionId).stream().findFirst();
        return requirePresent(workflow, "Payment workflow was not found");
    }

    private void insertPaymentStep(UUID workflowId, UUID attemptId, UUID transactionId,
            java.time.Instant now) {
        Integer prior = jdbc.queryForObject("SELECT COUNT(*) FROM origination_workflow_steps "
                + "WHERE workflow_id = ? AND step_name = 'DISBURSE'", Integer.class, workflowId);
        int attemptNumber = Objects.requireNonNull(prior, "Prior disbursement attempt count") + 1;
        jdbc.update("INSERT INTO origination_workflow_steps (attempt_id, workflow_id, step_name, "
                        + "state, attempt_number, started_at, payment_transaction_id) "
                        + "VALUES (?, ?, 'DISBURSE', 'RUNNING', ?, ?, ?)",
                attemptId, workflowId, attemptNumber, Timestamp.from(now), transactionId);
    }

    private void closePaymentStep(UUID attemptId, java.time.Instant now, String diagnostic) {
        int updated = jdbc.update("UPDATE origination_workflow_steps SET state = 'COMPLETED', "
                        + "completed_at = ?, failure_code = ? WHERE attempt_id = ? AND state = 'RUNNING'",
                Timestamp.from(now), diagnostic, attemptId);
        requireOne(updated, "Payment attempt step was already closed");
    }

    private void completePaymentStep(UUID attemptId, java.time.Instant now) {
        int updated = jdbc.update("UPDATE origination_workflow_steps SET state = 'COMPLETED', "
                        + "completed_at = ?, failure_code = NULL WHERE attempt_id = ? "
                        + "AND state IN ('RUNNING', 'RECONCILIATION_REQUIRED')",
                Timestamp.from(now), attemptId);
        requireOne(updated, "Payment attempt step was already closed");
    }

    private void markPaymentStepReconciliation(UUID attemptId, String diagnostic) {
        int updated = jdbc.update("UPDATE origination_workflow_steps SET state = 'RECONCILIATION_REQUIRED', "
                        + "failure_code = ? WHERE attempt_id = ? AND state = 'RUNNING'",
                diagnostic, attemptId);
        requireOne(updated, "Payment attempt step was already closed");
    }

    private PaymentTransaction updatePaymentOutcome(PaymentTransaction transaction,
            PaymentTransactionStatus status, String providerTransactionId, String diagnostic,
            java.time.Instant now) {
        PaymentTransactionStatus persistedStatus = status == PaymentTransactionStatus.UNKNOWN
                && transaction.status() != PaymentTransactionStatus.SUBMISSION_IN_PROGRESS
                ? PaymentTransactionStatus.RECONCILIATION_REQUIRED : status;
        int updated = jdbc.update("UPDATE payment_transactions SET status = ?, "
                        + "provider_transaction_id = COALESCE(?, provider_transaction_id), "
                        + "diagnostic_code = ?, updated_at = ?, version = version + 1 "
                        + "WHERE payment_transaction_id = ? AND tenant_id = ? AND version = ?",
                persistedStatus.name(), providerTransactionId, diagnostic, Timestamp.from(now),
                transaction.paymentTransactionId(), transaction.tenantId(), transaction.version());
        requireOne(updated, "Payment transaction changed concurrently");
        return new PaymentTransaction(transaction.paymentTransactionId(), transaction.tenantId(),
                transaction.loanId(), transaction.operationType(), transaction.idempotencyKey(),
                transaction.requestFingerprint(), transaction.amount(), transaction.currency(),
                transaction.owner(), providerTransactionId == null ? transaction.providerTransactionId()
                        : providerTransactionId, transaction.reconciliationKey(), persistedStatus,
                transaction.dispatchAttemptId(), transaction.dispatchClaimedAt(), now,
                transaction.version() + 1, diagnostic);
    }

    private static PaymentTransactionStatus paymentStatus(PaymentStatus status) {
        return switch (status) {
            case SUCCEEDED -> PaymentTransactionStatus.SUCCEEDED;
            case FAILED -> PaymentTransactionStatus.FAILED;
            case PROCESSING -> PaymentTransactionStatus.PROCESSING;
            case UNKNOWN -> PaymentTransactionStatus.UNKNOWN;
        };
    }

    private void verifySamePayment(PaymentTransaction transaction, String key,
            String fingerprint, PluginKey owner) {
        if (!transaction.idempotencyKey().equals(key)
                || !transaction.requestFingerprint().equals(fingerprint)) {
            throw new LendingCoreException("Payment idempotency key conflicts with existing request");
        }
        if (!transaction.owner().equals(owner)) {
            throw new LendingCoreException("Payment provider owner is immutable");
        }
    }

    private void insertPayment(PaymentTransaction transaction) {
        jdbc.update("INSERT INTO payment_transactions (payment_transaction_id, tenant_id, loan_id, "
                        + "operation_type, idempotency_key, request_fingerprint, amount, currency, "
                        + "plugin_id, plugin_version, provider_transaction_id, reconciliation_key, "
                        + "status, dispatch_attempt_id, dispatch_claimed_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                transaction.paymentTransactionId(), transaction.tenantId(), transaction.loanId(),
                transaction.operationType(), transaction.idempotencyKey(), transaction.requestFingerprint(),
                transaction.amount(), transaction.currency().getCurrencyCode(), transaction.owner().pluginId(),
                transaction.owner().pluginVersion(), transaction.providerTransactionId(),
                transaction.reconciliationKey(), transaction.status().name(), transaction.dispatchAttemptId(),
                Timestamp.from(transaction.dispatchClaimedAt()), Timestamp.from(transaction.updatedAt()),
                transaction.version());
    }

    Optional<PaymentTransaction> findPaymentTransaction(UUID tenantId, UUID transactionId) {
        return jdbc.query("SELECT * FROM payment_transactions WHERE tenant_id = ? "
                        + "AND payment_transaction_id = ?", this::mapPayment, tenantId, transactionId)
                .stream().findFirst();
    }

    Optional<PaymentTransaction> findPaymentByLoan(UUID tenantId, UUID loanId) {
        return jdbc.query("SELECT * FROM payment_transactions WHERE tenant_id = ? AND loan_id = ? "
                        + "AND operation_type = 'DISBURSE'", this::mapPayment, tenantId, loanId)
                .stream().findFirst();
    }

    Optional<PaymentTransaction> findPaymentByIdempotencyKey(UUID tenantId, String key) {
        return jdbc.query("SELECT * FROM payment_transactions WHERE tenant_id = ? "
                        + "AND operation_type = 'DISBURSE' AND idempotency_key = ?",
                this::mapPayment, tenantId, key).stream().findFirst();
    }

    private Optional<PaymentTransaction> findPaymentByIdempotencyKeyForUpdate(UUID tenantId, String key) {
        return jdbc.query("SELECT * FROM payment_transactions WHERE tenant_id = ? "
                        + "AND operation_type = 'DISBURSE' AND idempotency_key = ? FOR UPDATE",
                this::mapPayment, tenantId, key).stream().findFirst();
    }

    private Optional<PaymentTransaction> findPaymentForUpdate(UUID tenantId, UUID transactionId) {
        return jdbc.query("SELECT * FROM payment_transactions WHERE tenant_id = ? "
                        + "AND payment_transaction_id = ? FOR UPDATE",
                this::mapPayment, tenantId, transactionId).stream().findFirst();
    }

    Optional<DisbursementClaim> findDisbursement(UUID tenantId, UUID loanId) {
        Optional<PaymentTransaction> transaction = findPaymentByLoan(tenantId, loanId);
        if (transaction.isEmpty()) {
            return Optional.empty();
        }
        Loan loan = requirePresent(lending.findLoan(tenantId, loanId), "Payment loan was not found");
        RepaymentPlan plan = requirePresent(lending.findRepaymentPlan(tenantId, loanId),
                "Payment repayment plan was not found");
        OriginationWorkflow workflow = requirePresent(
                lending.findByApplication(tenantId, loan.applicationId()), "Payment workflow was not found");
        return Optional.of(new DisbursementClaim(transaction.orElseThrow(), loan, plan, workflow, false));
    }

    java.util.List<DisbursementClaim> findUnresolvedDisbursements() {
        java.util.List<PaymentTransaction> transactions = jdbc.query("SELECT * FROM payment_transactions "
                        + "WHERE operation_type = 'DISBURSE' AND status IN "
                        + "('SUBMISSION_IN_PROGRESS', 'PROCESSING', 'UNKNOWN', "
                        + "'RECONCILIATION_REQUIRED') ORDER BY updated_at",
                this::mapPayment);
        java.util.List<DisbursementClaim> claims = new java.util.ArrayList<>();
        for (PaymentTransaction transaction : transactions) {
            findDisbursement(transaction.tenantId(), transaction.loanId()).ifPresent(claims::add);
        }
        return java.util.List.copyOf(claims);
    }

    java.util.List<PaymentTransaction> findUnresolvedPaymentTransactions() {
        return jdbc.query("SELECT * FROM payment_transactions WHERE operation_type = 'DISBURSE' "
                        + "AND status IN ('SUBMISSION_IN_PROGRESS', 'PROCESSING', 'UNKNOWN', "
                        + "'RECONCILIATION_REQUIRED') ORDER BY updated_at",
                this::mapPayment);
    }

    void markPaymentOwnerUnavailable(UUID tenantId, UUID paymentTransactionId, UUID attemptId,
            java.time.Instant now) {
        transactions.execute(status -> {
            PaymentTransaction current = findPaymentForUpdate(tenantId, paymentTransactionId)
                    .orElse(null);
            PaymentTransaction transaction = requirePresent(Optional.ofNullable(current),
                    "Payment transaction was not found");
            if (!transaction.dispatchAttemptId().equals(attemptId) || !transaction.status().unresolved()) {
                return null;
            }
            updatePaymentOutcome(transaction, PaymentTransactionStatus.RECONCILIATION_REQUIRED,
                    null, "PAYMENT_OWNER_UNAVAILABLE", now);
            OriginationWorkflow workflow = workflowsForPayment(tenantId, paymentTransactionId);
            if (workflow.state() != OriginationWorkflowState.PAYMENT_RECONCILIATION_REQUIRED) {
                lending.updateWorkflowPayment(workflow, paymentTransactionId,
                        OriginationWorkflowState.PAYMENT_RECONCILIATION_REQUIRED, now);
                markPaymentStepReconciliation(transaction.dispatchAttemptId(), "PAYMENT_OWNER_UNAVAILABLE");
            }
            return null;
        });
    }

    boolean hasUnresolvedPaymentOwner(PluginKey owner) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM payment_transactions "
                        + "WHERE plugin_id = ? AND plugin_version = ? "
                        + "AND status IN ('SUBMISSION_IN_PROGRESS', 'PROCESSING', 'UNKNOWN', "
                        + "'RECONCILIATION_REQUIRED')",
                Integer.class, owner.pluginId(), owner.pluginVersion());
        return Objects.requireNonNull(count, "Payment owner count") > 0;
    }

    private PaymentTransaction mapPayment(ResultSet row, int ignoredRowNumber) throws SQLException {
        return new PaymentTransaction(row.getObject("payment_transaction_id", UUID.class),
                row.getObject("tenant_id", UUID.class), row.getObject("loan_id", UUID.class),
                row.getString("operation_type"), row.getString("idempotency_key"),
                row.getString("request_fingerprint"), row.getBigDecimal("amount"),
                Currency.getInstance(row.getString("currency")),
                new PluginKey(row.getString("plugin_id"), row.getString("plugin_version")),
                row.getString("provider_transaction_id"), row.getString("reconciliation_key"),
                PaymentTransactionStatus.valueOf(row.getString("status")),
                row.getObject("dispatch_attempt_id", UUID.class),
                row.getTimestamp("dispatch_claimed_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(), row.getLong("version"),
                row.getString("diagnostic_code"));
    }

    static void requireOne(int count, String message) {
        if (count != 1) {
            throw new LendingCoreException(message);
        }
    }

    static <T> T requirePresent(Optional<T> value, String message) {
        if (value.isEmpty()) {
            throw new LendingCoreException(message);
        }
        return value.orElseThrow();
    }
}
