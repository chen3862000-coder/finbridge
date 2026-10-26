package io.github.finbridge.lending.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RepaymentPlan(UUID planId, UUID loanId, UUID tenantId, BigDecimal totalPrincipal,
                           BigDecimal totalInterest, BigDecimal totalRepayable,
                           int installmentCount, RepaymentPlanStatus status, Instant createdAt,
                           Instant activatedAt, List<RepaymentInstallment> installments) {
    public RepaymentPlan {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(loanId, "loanId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(totalPrincipal, "totalPrincipal");
        Objects.requireNonNull(totalInterest, "totalInterest");
        Objects.requireNonNull(totalRepayable, "totalRepayable");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        installments = List.copyOf(Objects.requireNonNull(installments, "installments"));
        if (installmentCount < 1 || installments.size() != installmentCount
                || totalPrincipal.signum() <= 0 || totalInterest.signum() < 0
                || totalRepayable.compareTo(totalPrincipal.add(totalInterest)) != 0) {
            throw new IllegalArgumentException("Invalid repayment plan totals or installments");
        }
        if (status == RepaymentPlanStatus.ACTIVE && activatedAt == null) {
            throw new IllegalArgumentException("Active repayment plan requires activation time");
        }
    }

    public RepaymentPlan activate(Instant at, LocalDate disbursementDate) {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(disbursementDate, "disbursementDate");
        if (status != RepaymentPlanStatus.PENDING_ACTIVATION) {
            throw new IllegalStateException("Only a pending repayment plan can activate");
        }
        List<RepaymentInstallment> dated = installments.stream()
                .map(installment -> installment.withDueDate(
                        MonthlyDueDatePolicy.dueDate(disbursementDate,
                                installment.installmentNumber())))
                .toList();
        return new RepaymentPlan(planId, loanId, tenantId, totalPrincipal, totalInterest,
                totalRepayable, installmentCount, RepaymentPlanStatus.ACTIVE, createdAt, at, dated);
    }
}
