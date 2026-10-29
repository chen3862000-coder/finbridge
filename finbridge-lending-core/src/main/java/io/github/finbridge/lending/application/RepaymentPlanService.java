package io.github.finbridge.lending.application;

import io.github.finbridge.lending.domain.Loan;
import io.github.finbridge.lending.domain.RepaymentPlan;
import io.github.finbridge.lending.domain.RepaymentScheduleCalculator;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Builds draft repayment plans; activation and due-date assignment follow confirmed disbursement. */
@Service
public class RepaymentPlanService {
    private final RepaymentScheduleCalculator calculator;

    public RepaymentPlanService(RepaymentScheduleCalculator calculator) {
        this.calculator = calculator;
    }

    /** Generates a pending plan for the supplied loan without assigning due dates. */
    public RepaymentPlan generateDraft(Loan loan) {
        Objects.requireNonNull(loan, "loan");
        UUID planId = UUID.randomUUID();
        var installments = calculator.calculate(planId, loan.principal(),
                loan.annualInterestRatePercent(), loan.termMonths());
        BigDecimal interest = installments.stream().map(row -> row.interestAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new RepaymentPlan(planId, loan.loanId(), loan.tenantId(), loan.principal(), interest,
                loan.principal().add(interest), loan.termMonths(),
                io.github.finbridge.lending.domain.RepaymentPlanStatus.PENDING_ACTIVATION,
                loan.createdAt(), null, installments);
    }
}
