package io.github.finbridge.lending.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.finbridge.lending.domain.Loan;
import io.github.finbridge.lending.domain.LoanStatus;
import io.github.finbridge.lending.domain.RepaymentPlanStatus;
import io.github.finbridge.lending.domain.RepaymentScheduleCalculator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RepaymentPlanServiceTest {
    private final RepaymentPlanService service =
            new RepaymentPlanService(new RepaymentScheduleCalculator());

    @Test
    void createsPendingPlanWithConsistentTotals() {
        Loan loan = loan();
        var plan = service.generateDraft(loan);
        assertEquals(RepaymentPlanStatus.PENDING_ACTIVATION, plan.status());
        assertEquals(loan.loanId(), plan.loanId());
        assertEquals(new BigDecimal("10000.00"), plan.totalPrincipal());
        assertEquals(12, plan.installments().size());
        assertEquals(0, plan.totalRepayable().compareTo(
                plan.totalPrincipal().add(plan.totalInterest())));
    }

    @Test
    void rejectsNullLoan() {
        assertThrows(NullPointerException.class, () -> service.generateDraft(null));
    }

    @Test
    void rejectsNonAmortizingProductTerms() {
        Loan nonAmortizing = new Loan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("10000.00"), Currency.getInstance("CNY"),
                new BigDecimal("24000.000000"), 6, "CASH_LOAN_DEMO", 1,
                LoanStatus.PENDING_DISBURSEMENT, null, null, null, Instant.now(), 0);
        assertThrows(IllegalArgumentException.class, () -> service.generateDraft(nonAmortizing));
    }

    private static Loan loan() {
        return new Loan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("10000.00"), Currency.getInstance("CNY"),
                new BigDecimal("12.000000"), 12, "CASH_LOAN_DEMO", 1,
                LoanStatus.PENDING_DISBURSEMENT, null, null, null, Instant.now(), 0);
    }
}
