package io.github.finbridge.lending.persistence;

import io.github.finbridge.lending.domain.InstallmentStatus;
import io.github.finbridge.lending.domain.Loan;
import io.github.finbridge.lending.domain.LoanApplication;
import io.github.finbridge.lending.domain.LoanStatus;
import io.github.finbridge.lending.domain.RepaymentInstallment;
import io.github.finbridge.lending.domain.RepaymentPlan;
import io.github.finbridge.lending.domain.RepaymentPlanStatus;
import io.github.finbridge.lending.application.LendingCoreException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
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

    public JdbcLendingAggregateRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        PlatformTransactionManager transactionManager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
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
    public Optional<RepaymentPlan> findRepaymentPlan(UUID tenantId, UUID loanId) {
        return jdbc.query("SELECT * FROM repayment_plans WHERE tenant_id = ? AND loan_id = ?",
                this::mapPlan, tenantId, loanId).stream().findFirst();
    }

    @Override
    public Loan originate(Loan loan, RepaymentPlan plan) {
        try {
            Loan persisted = transactions.execute(status -> {
                insertLoan(loan);
                insertPlan(plan);
                insertInstallments(plan.installments());
                return loan;
            });
            return Objects.requireNonNull(persisted, "Persisted loan result");
        } catch (RuntimeException failure) {
            return findLoanByApplication(loan.tenantId(), loan.applicationId())
                    .filter(existing -> findRepaymentPlan(existing.tenantId(), existing.loanId()).isPresent())
                    .orElseThrow(() -> failure);
        }
    }

    @Override
    public OriginationWorkflow startRisk(UUID tenantId, UUID applicationId, java.time.Instant now) {
        OriginationWorkflow workflow = transactions.execute(status -> startRiskTransaction(
                tenantId, applicationId, now));
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

    private OriginationWorkflow startRiskTransaction(UUID tenantId, UUID applicationId,
                                                      java.time.Instant now) {
        LoanApplication application = jdbc.query("SELECT * FROM loan_applications "
                        + "WHERE tenant_id = ? AND application_id = ? FOR UPDATE",
                this::mapApplicationForWorkflow, tenantId, applicationId).stream().findFirst()
                .orElseThrow(() -> new LendingCoreException("Loan application was not found"));
        OriginationWorkflow current = findWorkflowForUpdate(tenantId, applicationId);
        if (current != null && current.state() == OriginationWorkflowState.RISK_COMPLETED) {
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
        persistWorkflowClaim(current, workflowId, applicationId, tenantId, attemptId, assessmentId,
                version, now);
        persistRiskStep(workflowId, attemptId, assessmentId, now);
        transitionApplicationToEvaluating(application, tenantId, applicationId, now);
        return new OriginationWorkflow(workflowId, applicationId, tenantId,
                OriginationWorkflowState.RISK_RUNNING, attemptId, assessmentId, null, version,
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
        var status = application.status();
        boolean allowed = status == io.github.finbridge.lending.domain.ApplicationStatus.SUBMITTED
                || status == io.github.finbridge.lending.domain.ApplicationStatus.RISK_EVALUATING;
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
                                     UUID applicationId, UUID tenantId, UUID attemptId,
                                     UUID assessmentId,
                                     long version, java.time.Instant now) {
        if (current == null) {
            jdbc.update("INSERT INTO origination_workflows (workflow_id, application_id, tenant_id, "
                            + "state, active_risk_attempt_id, active_assessment_id, version, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", workflowId, applicationId,
                    tenantId, OriginationWorkflowState.RISK_RUNNING.name(), attemptId, assessmentId, version,
                    Timestamp.from(now), Timestamp.from(now));
        } else {
            int updated = jdbc.update("UPDATE origination_workflows SET state = ?, active_risk_attempt_id = ?, "
                            + "active_assessment_id = ?, completed_assessment_id = NULL, "
                            + "version = ?, updated_at = ? "
                            + "WHERE workflow_id = ? AND version = ?",
                    OriginationWorkflowState.RISK_RUNNING.name(), attemptId, assessmentId, version,
                    Timestamp.from(now), workflowId, current.version());
            if (updated != 1) {
                throw new LendingCoreException("Risk workflow claim lost optimistic race");
            }
        }
    }

    private void persistRiskStep(UUID workflowId, UUID attemptId, UUID assessmentId,
                                 java.time.Instant now) {
        Integer priorAttempts = jdbc.queryForObject("SELECT COUNT(*) FROM origination_workflow_steps "
                + "WHERE workflow_id = ? AND step_name = 'RISK'", Integer.class, workflowId);
        int attemptNumber = Objects.requireNonNull(priorAttempts, "Workflow risk attempt count") + 1;
        jdbc.update("INSERT INTO origination_workflow_steps (attempt_id, workflow_id, step_name, "
                        + "state, attempt_number, started_at, assessment_id) "
                        + "VALUES (?, ?, 'RISK', 'RUNNING', ?, ?, ?)", attemptId, workflowId,
                attemptNumber, Timestamp.from(now), assessmentId);
    }

    private void transitionApplicationToEvaluating(LoanApplication application, UUID tenantId,
                                                    UUID applicationId, java.time.Instant now) {
        if (application.status() == io.github.finbridge.lending.domain.ApplicationStatus.SUBMITTED) {
            int updated = jdbc.update("UPDATE loan_applications SET status = ?, updated_at = ?, "
                            + "version = version + 1 WHERE application_id = ? AND tenant_id = ? AND version = ?",
                    io.github.finbridge.lending.domain.ApplicationStatus.RISK_EVALUATING.name(),
                    Timestamp.from(now), applicationId, tenantId, application.version());
            if (updated != 1) {
                throw new LendingCoreException("Application changed while risk evaluation was starting");
            }
        }
    }

    private LoanApplication mapApplicationForWorkflow(ResultSet row, int ignoredRowNumber) throws SQLException {
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

    private OriginationWorkflow findWorkflowForUpdate(UUID tenantId, UUID applicationId) {
        return jdbc.query("SELECT * FROM origination_workflows WHERE tenant_id = ? AND application_id = ? "
                        + "FOR UPDATE", this::mapWorkflow, tenantId, applicationId)
                .stream().findFirst().orElse(null);
    }

    private OriginationWorkflow mapWorkflow(ResultSet row, int ignoredRowNumber) throws SQLException {
        return new OriginationWorkflow(row.getObject("workflow_id", UUID.class),
                row.getObject("application_id", UUID.class), row.getObject("tenant_id", UUID.class),
                OriginationWorkflowState.valueOf(row.getString("state")),
                row.getObject("active_risk_attempt_id", UUID.class),
                row.getObject("active_assessment_id", UUID.class),
                row.getObject("completed_assessment_id", UUID.class), row.getLong("version"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }

    private void insertLoan(Loan loan) {
        jdbc.update("INSERT INTO loans (loan_id, application_id, tenant_id, principal, currency, "
                        + "annual_interest_rate_percent, term_months, product_code, product_version, "
                        + "status, disbursement_transaction_id, disbursed_at, activated_at, created_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                loan.loanId(), loan.applicationId(), loan.tenantId(), loan.principal(),
                loan.currency().getCurrencyCode(), loan.annualInterestRatePercent(), loan.termMonths(),
                loan.productCode(), loan.productVersion(), loan.status().name(),
                loan.disbursementTransactionId(), timestamp(loan.disbursedAt()),
                timestamp(loan.activatedAt()), Timestamp.from(loan.createdAt()), loan.version());
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
                    installment.interestAmount(), installment.totalAmount(),
                    installment.remainingPrincipal(), installment.status().name());
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
}
