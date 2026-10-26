package io.github.finbridge.lending.persistence;

import io.github.finbridge.lending.domain.InstallmentStatus;
import io.github.finbridge.lending.domain.Loan;
import io.github.finbridge.lending.domain.LoanStatus;
import io.github.finbridge.lending.domain.RepaymentInstallment;
import io.github.finbridge.lending.domain.RepaymentPlan;
import io.github.finbridge.lending.domain.RepaymentPlanStatus;
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
public class JdbcLendingAggregateRepository implements LendingAggregateRepository {
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
