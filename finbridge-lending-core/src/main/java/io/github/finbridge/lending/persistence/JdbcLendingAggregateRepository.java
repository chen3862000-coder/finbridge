package io.github.finbridge.lending.persistence;

import io.github.finbridge.lending.application.LendingCoreException;
import io.github.finbridge.lending.domain.InstallmentStatus;
import io.github.finbridge.lending.domain.Loan;
import io.github.finbridge.lending.domain.LoanApplication;
import io.github.finbridge.lending.domain.LoanStatus;
import io.github.finbridge.lending.domain.PaymentTransaction;
import io.github.finbridge.lending.domain.RepaymentInstallment;
import io.github.finbridge.lending.domain.RepaymentPlan;
import io.github.finbridge.lending.domain.RepaymentPlanStatus;
import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.spi.payment.PaymentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.sql.Timestamp;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcLendingAggregateRepository implements LendingAggregateRepository,
        OriginationWorkflowRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JdbcDisbursementRepository disbursements;

    public JdbcLendingAggregateRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        PlatformTransactionManager transactionManager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
        this.disbursements = new JdbcDisbursementRepository(jdbc, transactions, this);
    }

    @Override
    public Optional<Loan> findLoanByApplication(UUID tenantId, UUID applicationId) {
        return jdbc.query("SELECT * FROM loans WHERE tenant_id = ? AND application_id = ?",
                this::mapLoan, tenantId, applicationId).stream().findFirst();
    }

    @Override
    public Optional<Loan> findLoan(UUID tenantId, UUID loanId) {
        return jdbc.query("SELECT * FROM loans WHERE tenant_id = ? AND loan_id = ?",
                this::mapLoan, tenantId, loanId).stream().findFirst();
    }

    @Override
    public List<Loan> findRecentLoans(UUID tenantId, int limit) {
        requireLimit(limit);
        return jdbc.query("SELECT * FROM loans WHERE tenant_id = ? "
                        + "ORDER BY created_at DESC, loan_id DESC LIMIT ?",
                this::mapLoan, tenantId, limit);
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }

    @Override
    public Optional<RepaymentPlan> findRepaymentPlan(UUID tenantId, UUID loanId) {
        return jdbc.query("SELECT * FROM repayment_plans WHERE tenant_id = ? AND loan_id = ?",
                this::mapPlan, tenantId, loanId).stream().findFirst();
    }

    @Override
    public Loan originate(Loan loan, RepaymentPlan plan) {
        return persistLoanAndPlan(loan, plan, false);
    }

    @Override
    public Loan originateApproved(Loan loan, RepaymentPlan plan) {
        return persistLoanAndPlan(loan, plan, true);
    }

    private Loan persistLoanAndPlan(Loan loan, RepaymentPlan plan, boolean approvedWorkflow) {
        try {
            Loan persisted = transactions.execute(status -> {
                insertLoan(loan);
                insertPlan(plan);
                insertInstallments(plan.installments());
                if (approvedWorkflow) {
                    advanceApprovedApplicationToPaymentPending(loan);
                }
                return loan;
            });
            return Objects.requireNonNull(persisted, "Persisted loan result");
        } catch (RuntimeException failure) {
            return findLoanByApplication(loan.tenantId(), loan.applicationId())
                    .filter(existing -> findRepaymentPlan(existing.tenantId(), existing.loanId()).isPresent())
                    .orElseThrow(() -> failure);
        }
    }

    private void advanceApprovedApplicationToPaymentPending(Loan loan) {
        int applicationUpdated = jdbc.update("UPDATE loan_applications SET status = 'ORIGINATION_IN_PROGRESS', "
                        + "updated_at = ?, version = version + 1 WHERE tenant_id = ? AND application_id = ? "
                        + "AND status = 'APPROVED'",
                Timestamp.from(loan.createdAt()), loan.tenantId(), loan.applicationId());
        requireOne(applicationUpdated, "Approved application changed before loan origination");
        int workflowUpdated = jdbc.update("UPDATE origination_workflows SET version = version + 1, "
                        + "updated_at = ? WHERE tenant_id = ? AND application_id = ? "
                        + "AND state = 'PAYMENT_PENDING' AND completed_assessment_id IS NOT NULL",
                Timestamp.from(loan.createdAt()), loan.tenantId(), loan.applicationId());
        requireOne(workflowUpdated, "Risk workflow is not ready for payment intent");
    }

    @Override
    public OriginationWorkflow startRisk(UUID tenantId, UUID applicationId, java.time.Instant now) {
        OriginationWorkflow workflow = transactions.execute(status ->
                startRiskTransaction(tenantId, applicationId, now));
        return Objects.requireNonNull(workflow, "Workflow transaction result");
    }

    @Override
    public OriginationWorkflow resumeRisk(UUID tenantId, UUID applicationId, java.time.Instant now) {
        OriginationWorkflow workflow = findByApplication(tenantId, applicationId)
                .orElseThrow(() -> new LendingCoreException("Origination workflow was not found"));
        if (workflow.state() == OriginationWorkflowState.RISK_COMPLETED) {
            return workflow;
        }
        if (workflow.state() != OriginationWorkflowState.RISK_RUNNING) {
            throw new LendingCoreException("Risk workflow is not resumable from " + workflow.state());
        }
        return transactions.execute(status -> startRiskTransaction(tenantId, applicationId, now));
    }

    @Override
    public List<WorkflowRecoveryCandidate> interruptedRiskWorkflows() {
        return jdbc.query("SELECT tenant_id, application_id "
                        + "FROM origination_workflows WHERE state = 'RISK_RUNNING' ORDER BY updated_at",
                (row, ignored) -> new WorkflowRecoveryCandidate(row.getObject("tenant_id", UUID.class),
                        row.getObject("application_id", UUID.class)));
    }

    @Override
    public DisbursementClaim claimDisbursement(UUID tenantId, UUID loanId, PluginKey owner,
            UUID transactionId, String idempotencyKey, String requestFingerprint,
            UUID attemptId, java.time.Instant now) {
        return disbursements.claimDisbursement(tenantId, loanId, owner, transactionId,
                idempotencyKey, requestFingerprint, attemptId, now);
    }

    @Override
    public DisbursementClaim claimDisbursement(UUID tenantId, UUID loanId, PluginKey owner,
            String idempotencyKey, String requestFingerprint, UUID attemptId, java.time.Instant now) {
        return claimDisbursement(tenantId, loanId, owner, UUID.randomUUID(), idempotencyKey,
                requestFingerprint, attemptId, now);
    }

    @Override
    public DisbursementClaim recordDisbursementOutcome(UUID tenantId, UUID paymentTransactionId,
            UUID expectedAttemptId, PaymentStatus status, String providerTransactionId,
            String diagnosticCode, java.time.Instant confirmedAt,
            java.time.LocalDate disbursementDate, java.time.Instant now) {
        return disbursements.recordOutcome(tenantId, paymentTransactionId, expectedAttemptId,
                status, providerTransactionId, diagnosticCode, confirmedAt, disbursementDate, now);
    }

    @Override
    public DisbursementClaim markInterruptedDispatchUnknown(UUID tenantId, UUID transactionId,
            java.time.Instant now) {
        return disbursements.markInterrupted(tenantId, transactionId, now);
    }

    @Override
    public Optional<PaymentTransaction> findPaymentTransaction(UUID tenantId, UUID transactionId) {
        return disbursements.findPaymentTransaction(tenantId, transactionId);
    }

    @Override
    public Optional<PaymentTransaction> findPaymentByLoan(UUID tenantId, UUID loanId) {
        return disbursements.findPaymentByLoan(tenantId, loanId);
    }

    @Override
    public Optional<PaymentTransaction> findPaymentByIdempotencyKey(UUID tenantId, String key) {
        return disbursements.findPaymentByIdempotencyKey(tenantId, key);
    }

    @Override
    public Optional<DisbursementClaim> findDisbursement(UUID tenantId, UUID loanId) {
        return disbursements.findDisbursement(tenantId, loanId);
    }

    @Override
    public List<DisbursementClaim> findUnresolvedDisbursements() {
        return disbursements.findUnresolvedDisbursements();
    }

    @Override
    public List<PaymentTransaction> findUnresolvedPaymentTransactions() {
        return disbursements.findUnresolvedPaymentTransactions();
    }

    @Override
    public void markPaymentOwnerUnavailable(UUID tenantId, UUID paymentTransactionId,
            UUID attemptId, java.time.Instant now) {
        disbursements.markPaymentOwnerUnavailable(tenantId, paymentTransactionId, attemptId, now);
    }

    @Override
    public boolean hasUnresolvedPaymentOwner(PluginKey owner) {
        return disbursements.hasUnresolvedPaymentOwner(owner);
    }

    OriginationWorkflow updateWorkflowPayment(OriginationWorkflow workflow, UUID transactionId,
            OriginationWorkflowState state, java.time.Instant now) {
        int updated = jdbc.update("UPDATE origination_workflows SET state = ?, "
                        + "payment_transaction_id = ?, version = version + 1, updated_at = ? "
                        + "WHERE workflow_id = ? AND version = ?",
                state.name(), transactionId, Timestamp.from(now), workflow.workflowId(), workflow.version());
        requireOne(updated, "Origination workflow payment transition lost optimistic race");
        return new OriginationWorkflow(workflow.workflowId(), workflow.applicationId(),
                workflow.tenantId(), state, workflow.activeRiskAttemptId(), workflow.activeAssessmentId(),
                workflow.completedAssessmentId(), transactionId, workflow.version() + 1,
                workflow.createdAt(), now);
    }

    Optional<Loan> findLoanForUpdate(UUID tenantId, UUID loanId) {
        return jdbc.query("SELECT * FROM loans WHERE tenant_id = ? AND loan_id = ? FOR UPDATE",
                this::mapLoan, tenantId, loanId).stream().findFirst();
    }

    void updateLoan(Loan loan, long expectedVersion) {
        int updated = jdbc.update("UPDATE loans SET status = ?, disbursement_transaction_id = ?, "
                        + "disbursed_at = ?, activated_at = ?, version = version + 1 "
                        + "WHERE tenant_id = ? AND loan_id = ? AND version = ?",
                loan.status().name(), loan.disbursementTransactionId(), timestamp(loan.disbursedAt()),
                timestamp(loan.activatedAt()), loan.tenantId(), loan.loanId(), expectedVersion);
        requireOne(updated, "Loan changed concurrently during disbursement");
    }

    OriginationWorkflow findWorkflowForUpdate(UUID tenantId, UUID applicationId) {
        return jdbc.query("SELECT * FROM origination_workflows WHERE tenant_id = ? AND application_id = ? "
                        + "FOR UPDATE", this::mapWorkflow, tenantId, applicationId)
                .stream().findFirst().orElse(null);
    }

    OriginationWorkflow mapWorkflow(ResultSet row, int ignoredRowNumber) throws SQLException {
        return new OriginationWorkflow(row.getObject("workflow_id", UUID.class),
                row.getObject("application_id", UUID.class), row.getObject("tenant_id", UUID.class),
                OriginationWorkflowState.valueOf(row.getString("state")),
                row.getObject("active_risk_attempt_id", UUID.class),
                row.getObject("active_assessment_id", UUID.class),
                row.getObject("completed_assessment_id", UUID.class),
                row.getObject("payment_transaction_id", UUID.class), row.getLong("version"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }

    private OriginationWorkflow startRiskTransaction(UUID tenantId, UUID applicationId,
            java.time.Instant now) {
        LoanApplication application = jdbc.query("SELECT * FROM loan_applications "
                        + "WHERE tenant_id = ? AND application_id = ? FOR UPDATE",
                this::mapApplication, tenantId, applicationId).stream().findFirst()
                .orElseThrow(() -> new LendingCoreException("Loan application was not found"));
        OriginationWorkflow current = findWorkflowForUpdate(tenantId, applicationId);
        if (current != null && (current.state() == OriginationWorkflowState.RISK_COMPLETED
                || current.state() == OriginationWorkflowState.PAYMENT_PENDING)) {
            return current;
        }
        validateRiskStart(application, current);
        UUID workflowId = current == null ? UUID.randomUUID() : current.workflowId();
        long version = current == null ? 0 : current.version() + 1;
        UUID attemptId = UUID.randomUUID();
        UUID assessmentId = current == null ? UUID.randomUUID() : current.activeAssessmentId();
        if (assessmentId == null) {
            throw new LendingCoreException("Running workflow has no reserved risk assessment ID");
        }
        retirePendingAttempt(current, now);
        persistPendingAssessment(current, applicationId, tenantId, assessmentId, now);
        persistWorkflowClaim(current, workflowId, applicationId, tenantId, attemptId,
                assessmentId, version, now);
        persistRiskStep(workflowId, attemptId, assessmentId, now);
        transitionApplicationToEvaluating(application, tenantId, applicationId, now);
        return new OriginationWorkflow(workflowId, applicationId, tenantId,
                OriginationWorkflowState.RISK_RUNNING, attemptId, assessmentId, null, null, version,
                current == null ? now : current.createdAt(), now);
    }

    private void persistPendingAssessment(OriginationWorkflow current, UUID applicationId,
            UUID tenantId, UUID assessmentId, java.time.Instant now) {
        if (current == null) {
            jdbc.update("INSERT INTO risk_assessments (assessment_id, application_id, tenant_id, "
                            + "status, reason_codes, provider_execution_references, policy_version, created_at) "
                            + "VALUES (?, ?, ?, 'PENDING', '{}', '[]'::jsonb, 'demo-risk-policy-1', ?)",
                    assessmentId, applicationId, tenantId, Timestamp.from(now));
            return;
        }
        jdbc.update("UPDATE risk_assessments SET status = 'PENDING', completed_at = NULL, decision = NULL, "
                        + "credit_score = NULL, blacklist_hit = NULL, fraud_signal = NULL, reason_codes = '{}', "
                        + "provider_execution_references = '[]'::jsonb, created_at = ? "
                        + "WHERE assessment_id = ? AND tenant_id = ? AND status IN ('PENDING', 'FAILED')",
                Timestamp.from(now), assessmentId, tenantId);
    }

    private void validateRiskStart(LoanApplication application, OriginationWorkflow current) {
        var applicationStatus = application.status();
        boolean allowed = applicationStatus == io.github.finbridge.lending.domain.ApplicationStatus.SUBMITTED
                || applicationStatus == io.github.finbridge.lending.domain.ApplicationStatus.RISK_EVALUATING;
        if (!allowed || (current != null
                && current.state() != OriginationWorkflowState.RISK_RUNNING)) {
            throw new LendingCoreException("Application cannot start or resume risk evaluation");
        }
    }

    private void retirePendingAttempt(OriginationWorkflow current, java.time.Instant now) {
        if (current == null || current.activeRiskAttemptId() == null) {
            return;
        }
        jdbc.update("UPDATE origination_workflow_steps SET state = 'INTERRUPTED', completed_at = ?, "
                        + "failure_code = 'RISK_RETRY' WHERE attempt_id = ? AND state = 'RUNNING'",
                Timestamp.from(now), current.activeRiskAttemptId());
        jdbc.update("UPDATE risk_assessments SET status = 'FAILED', completed_at = ? "
                        + "WHERE assessment_id IN (SELECT assessment_id FROM origination_workflow_steps "
                        + "WHERE attempt_id = ?) AND status = 'PENDING'",
                Timestamp.from(now), current.activeRiskAttemptId());
    }

    private void persistWorkflowClaim(OriginationWorkflow current, UUID workflowId,
            UUID applicationId, UUID tenantId, UUID attemptId, UUID assessmentId,
            long version, java.time.Instant now) {
        if (current == null) {
            jdbc.update("INSERT INTO origination_workflows (workflow_id, application_id, tenant_id, "
                            + "state, active_risk_attempt_id, active_assessment_id, version, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", workflowId, applicationId,
                    tenantId, OriginationWorkflowState.RISK_RUNNING.name(), attemptId, assessmentId, version,
                    Timestamp.from(now), Timestamp.from(now));
            return;
        }
        int updated = jdbc.update("UPDATE origination_workflows SET state = ?, active_risk_attempt_id = ?, "
                        + "active_assessment_id = ?, completed_assessment_id = NULL, version = ?, updated_at = ? "
                        + "WHERE workflow_id = ? AND version = ?",
                OriginationWorkflowState.RISK_RUNNING.name(), attemptId, assessmentId, version,
                Timestamp.from(now), workflowId, current.version());
        requireOne(updated, "Risk workflow claim lost optimistic race");
    }

    private void persistRiskStep(UUID workflowId, UUID attemptId, UUID assessmentId,
            java.time.Instant now) {
        Integer prior = jdbc.queryForObject("SELECT COUNT(*) FROM origination_workflow_steps "
                + "WHERE workflow_id = ? AND step_name = 'RISK'", Integer.class, workflowId);
        int attemptNumber = Objects.requireNonNull(prior, "Workflow risk attempt count") + 1;
        jdbc.update("INSERT INTO origination_workflow_steps (attempt_id, workflow_id, step_name, "
                        + "state, attempt_number, started_at, assessment_id) "
                        + "VALUES (?, ?, 'RISK', 'RUNNING', ?, ?, ?)",
                attemptId, workflowId, attemptNumber, Timestamp.from(now), assessmentId);
    }

    private void transitionApplicationToEvaluating(LoanApplication application, UUID tenantId,
            UUID applicationId, java.time.Instant now) {
        if (application.status() != io.github.finbridge.lending.domain.ApplicationStatus.SUBMITTED) {
            return;
        }
        int updated = jdbc.update("UPDATE loan_applications SET status = ?, updated_at = ?, "
                        + "version = version + 1 WHERE application_id = ? AND tenant_id = ? AND version = ?",
                io.github.finbridge.lending.domain.ApplicationStatus.RISK_EVALUATING.name(),
                Timestamp.from(now), applicationId, tenantId, application.version());
        requireOne(updated, "Application changed while risk evaluation was starting");
    }

    private LoanApplication mapApplication(ResultSet row, int ignoredRowNumber) throws SQLException {
        return new LoanApplication(row.getObject("application_id", UUID.class),
                row.getObject("tenant_id", UUID.class), row.getString("applicant_id"),
                row.getString("product_code"), row.getInt("product_version"),
                row.getBigDecimal("requested_amount"), Currency.getInstance(row.getString("currency")),
                row.getInt("term_months"),
                io.github.finbridge.lending.domain.ApplicationStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }

    @Override
    public Optional<OriginationWorkflow> findByApplication(UUID tenantId, UUID applicationId) {
        return jdbc.query("SELECT * FROM origination_workflows WHERE tenant_id = ? AND application_id = ?",
                this::mapWorkflow, tenantId, applicationId).stream().findFirst();
    }

    @Override
    public List<WorkflowStepView> findSteps(UUID tenantId, UUID applicationId) {
        return jdbc.query("SELECT step.attempt_id, step.step_name, step.state, step.attempt_number, "
                        + "step.started_at, step.completed_at, step.assessment_id, "
                        + "step.payment_transaction_id, step.failure_code "
                        + "FROM origination_workflow_steps step JOIN origination_workflows workflow "
                        + "ON workflow.workflow_id = step.workflow_id "
                        + "WHERE workflow.tenant_id = ? AND workflow.application_id = ? "
                        + "ORDER BY step.started_at, step.attempt_number",
                (row, ignored) -> new WorkflowStepView(row.getObject("attempt_id", UUID.class),
                        row.getString("step_name"), row.getString("state"), row.getInt("attempt_number"),
                        row.getObject("started_at", OffsetDateTime.class).toInstant(),
                        timestampInstant(row.getObject("completed_at", OffsetDateTime.class)),
                        row.getObject("assessment_id", UUID.class),
                        row.getObject("payment_transaction_id", UUID.class), row.getString("failure_code")),
                tenantId, applicationId);
    }

    private static java.time.Instant timestampInstant(OffsetDateTime timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private void insertLoan(Loan loan) {
        jdbc.update("INSERT INTO loans (loan_id, application_id, tenant_id, principal, currency, "
                        + "annual_interest_rate_percent, term_months, product_code, product_version, "
                        + "status, disbursement_transaction_id, disbursed_at, activated_at, created_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                loan.loanId(), loan.applicationId(), loan.tenantId(), loan.principal(),
                loan.currency().getCurrencyCode(), loan.annualInterestRatePercent(), loan.termMonths(),
                loan.productCode(), loan.productVersion(), loan.status().name(),
                loan.disbursementTransactionId(), timestamp(loan.disbursedAt()), timestamp(loan.activatedAt()),
                Timestamp.from(loan.createdAt()), loan.version());
    }

    private void insertPlan(RepaymentPlan plan) {
        jdbc.update("INSERT INTO repayment_plans (plan_id, loan_id, tenant_id, total_principal, "
                        + "total_interest, total_repayable, installment_count, status, created_at, activated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                plan.planId(), plan.loanId(), plan.tenantId(), plan.totalPrincipal(),
                plan.totalInterest(), plan.totalRepayable(), plan.installmentCount(),
                plan.status().name(), Timestamp.from(plan.createdAt()), timestamp(plan.activatedAt()));
    }

    private void insertInstallments(List<RepaymentInstallment> installments) {
        for (RepaymentInstallment installment : installments) {
            jdbc.update("INSERT INTO repayment_installments (installment_id, plan_id, "
                            + "installment_number, due_date, opening_principal, principal_amount, "
                            + "interest_amount, total_amount, remaining_principal, status) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    installment.installmentId(), installment.planId(), installment.installmentNumber(),
                    installment.dueDate(), installment.openingPrincipal(), installment.principalAmount(),
                    installment.interestAmount(), installment.totalAmount(), installment.remainingPrincipal(),
                    installment.status().name());
        }
    }

    private static Timestamp timestamp(java.time.Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Loan mapLoan(ResultSet row, int ignoredRowNumber) throws SQLException {
        return new Loan(row.getObject("loan_id", UUID.class),
                row.getObject("application_id", UUID.class), row.getObject("tenant_id", UUID.class),
                row.getBigDecimal("principal"), Currency.getInstance(row.getString("currency")),
                row.getBigDecimal("annual_interest_rate_percent"), row.getInt("term_months"),
                row.getString("product_code"), row.getInt("product_version"),
                LoanStatus.valueOf(row.getString("status")),
                row.getObject("disbursement_transaction_id", UUID.class),
                row.getTimestamp("disbursed_at") == null ? null : row.getTimestamp("disbursed_at").toInstant(),
                row.getTimestamp("activated_at") == null ? null : row.getTimestamp("activated_at").toInstant(),
                row.getTimestamp("created_at").toInstant(), row.getLong("version"));
    }

    private RepaymentPlan mapPlan(ResultSet row, int ignoredRowNumber) throws SQLException {
        UUID planId = row.getObject("plan_id", UUID.class);
        List<RepaymentInstallment> installments = jdbc.query(
                "SELECT * FROM repayment_installments WHERE plan_id = ? ORDER BY installment_number",
                (installmentRow, index) -> new RepaymentInstallment(
                        installmentRow.getObject("installment_id", UUID.class), planId,
                        installmentRow.getInt("installment_number"),
                        installmentRow.getDate("due_date") == null ? null
                                : installmentRow.getDate("due_date").toLocalDate(),
                        installmentRow.getBigDecimal("opening_principal"),
                        installmentRow.getBigDecimal("principal_amount"),
                        installmentRow.getBigDecimal("interest_amount"),
                        installmentRow.getBigDecimal("total_amount"),
                        installmentRow.getBigDecimal("remaining_principal"),
                        InstallmentStatus.valueOf(installmentRow.getString("status"))), planId);
        return new RepaymentPlan(planId, row.getObject("loan_id", UUID.class),
                row.getObject("tenant_id", UUID.class), row.getBigDecimal("total_principal"),
                row.getBigDecimal("total_interest"), row.getBigDecimal("total_repayable"),
                row.getInt("installment_count"), RepaymentPlanStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("activated_at") == null ? null : row.getTimestamp("activated_at").toInstant(),
                installments);
    }

    static void requireOne(int count, String message) {
        if (count != 1) {
            throw new LendingCoreException(message);
        }
    }
}
