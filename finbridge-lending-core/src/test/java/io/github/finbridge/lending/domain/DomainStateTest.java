package io.github.finbridge.lending.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainStateTest {
    @Test
    void applicationEnforcesRiskAndOriginationStateTransitions() {
        LoanApplication application = application();
        LoanApplication evaluating = application.transitionTo(ApplicationStatus.RISK_EVALUATING,
                Instant.now());
        LoanApplication approved = evaluating.transitionTo(ApplicationStatus.APPROVED, Instant.now());
        LoanApplication originating = approved.transitionTo(ApplicationStatus.ORIGINATION_IN_PROGRESS,
                Instant.now());
        assertEquals(ApplicationStatus.ORIGINATED,
                originating.transitionTo(ApplicationStatus.ORIGINATED, Instant.now()).status());
        assertThrows(IllegalStateException.class,
                () -> application.transitionTo(ApplicationStatus.ORIGINATED, Instant.now()));
        assertThrows(IllegalStateException.class,
                () -> evaluating.transitionTo(ApplicationStatus.ORIGINATED, Instant.now()));
    }

    @Test
    void productValidatesCurrencyAmountAndTerms() {
        LoanProduct product = product();
        product.validate(new BigDecimal("1000.00"), 3, Currency.getInstance("CNY"));
        assertThrows(IllegalArgumentException.class, () -> product.validate(
                new BigDecimal("999.00"), 3, Currency.getInstance("CNY")));
        assertThrows(IllegalArgumentException.class, () -> product.validate(
                new BigDecimal("1000.00"), 9, Currency.getInstance("CNY")));
        assertThrows(IllegalArgumentException.class, () -> product.validate(
                new BigDecimal("1000.00"), 3, Currency.getInstance("USD")));
        assertThrows(IllegalArgumentException.class, () -> product.validate(
                new BigDecimal("1000.001"), 3, Currency.getInstance("CNY")));
        assertThrows(IllegalArgumentException.class, () -> new LoanProduct(" ", 1,
                Currency.getInstance("CNY"), new BigDecimal("1000.00"),
                new BigDecimal("2000.00"), Set.of(3), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new LoanProduct("INVALID", 1,
                Currency.getInstance("CNY"), new BigDecimal("1000.00"),
                new BigDecimal("2000.00"), Set.of(0), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new LoanProduct("INVALID", 1,
                Currency.getInstance("CNY"), new BigDecimal("3000.00"),
                new BigDecimal("2000.00"), Set.of(3), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new LoanProduct("INVALID", 1,
                Currency.getInstance("CNY"), new BigDecimal("1000.00"),
                new BigDecimal("2000.00"), Set.of(3), new BigDecimal("-1.00")));
        assertThrows(IllegalArgumentException.class, () -> new LoanProduct("INVALID", 1,
                Currency.getInstance("CNY"), new BigDecimal("1000.00"),
                new BigDecimal("2000.00"), Set.of(), BigDecimal.ZERO));
    }

    @Test
    void loanCannotActivateBeforePaymentIntentAndConfirmedTimestamp() {
        Loan loan = loan();
        assertThrows(IllegalStateException.class, () -> loan.activateAfterConfirmedDisbursement(Instant.now()));
        Loan processing = loan.markDisbursementProcessing(UUID.randomUUID());
        assertThrows(IllegalStateException.class,
                () -> processing.markDisbursementProcessing(UUID.randomUUID()));
        Loan active = processing.activateAfterConfirmedDisbursement(Instant.now());
        assertEquals(LoanStatus.ACTIVE, active.status());
        assertThrows(IllegalStateException.class, () -> active.markDisbursementFailed());
        assertEquals(LoanStatus.DISBURSEMENT_FAILED, loan.markDisbursementFailed().status());
        assertThrows(NullPointerException.class, () -> loan.activateAfterConfirmedDisbursement(null));
    }

    @Test
    void dueDatesClampMonthEndsWithoutLosingOriginalAnchor() {
        assertEquals(LocalDate.of(2025, 2, 28),
                MonthlyDueDatePolicy.dueDate(LocalDate.of(2025, 1, 31), 1));
        assertEquals(LocalDate.of(2025, 3, 31),
                MonthlyDueDatePolicy.dueDate(LocalDate.of(2025, 1, 31), 2));
        assertEquals(LocalDate.of(2024, 2, 29),
                MonthlyDueDatePolicy.dueDate(LocalDate.of(2024, 1, 31), 1));
        assertThrows(IllegalArgumentException.class,
                () -> MonthlyDueDatePolicy.dueDate(LocalDate.now(), 0));
    }

    @Test
    void repaymentPlanActivatesOnlyFromPendingAndFinalizesDueDates() {
        Loan loan = loan().markDisbursementProcessing(UUID.randomUUID());
        UUID planId = UUID.randomUUID();
        List<RepaymentInstallment> installments = new RepaymentScheduleCalculator().calculate(planId,
                loan.principal(), loan.annualInterestRatePercent(), loan.termMonths());
        BigDecimal interest = installments.stream().map(RepaymentInstallment::interestAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        RepaymentPlan pending = new RepaymentPlan(planId, loan.loanId(), loan.tenantId(),
                loan.principal(), interest, loan.principal().add(interest), loan.termMonths(),
                RepaymentPlanStatus.PENDING_ACTIVATION, Instant.now(), null, installments);
        RepaymentPlan active = pending.activate(Instant.now(), LocalDate.of(2025, 1, 31));
        assertEquals(RepaymentPlanStatus.ACTIVE, active.status());
        assertEquals(LocalDate.of(2025, 2, 28), active.installments().getFirst().dueDate());
        assertThrows(IllegalStateException.class,
                () -> active.activate(Instant.now(), LocalDate.now()));
    }

    @Test
    void riskAssessmentRequiresCompleteDecisionAndChronologicalTimestamps() {
        UUID assessmentId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> new RiskAssessment(assessmentId,
                applicationId, tenantId, AssessmentStatus.COMPLETED, 720, false, "NONE", null,
                List.of(), List.of(), "policy-1", created, created.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new RiskAssessment(assessmentId,
                applicationId, tenantId, AssessmentStatus.COMPLETED, 720, false, "NONE",
                AssessmentDecision.APPROVE, List.of(), List.of(), "policy-1", created,
                created.minusSeconds(1)));
        RiskAssessment pending = new RiskAssessment(assessmentId, applicationId, tenantId,
                AssessmentStatus.PENDING, null, null, null, null, List.of(), List.of(),
                "policy-1", created, null);
        assertEquals(AssessmentStatus.PENDING, pending.status());
        assertThrows(IllegalArgumentException.class, () -> new RiskAssessment(assessmentId,
                applicationId, tenantId, AssessmentStatus.PENDING, 1001, null, null, null,
                List.of(), List.of(), "policy-1", created, null));
    }

    private static LoanApplication application() {
        Instant now = Instant.now();
        return new LoanApplication(UUID.randomUUID(), UUID.randomUUID(), "synthetic-applicant",
                "CASH_LOAN_DEMO", 1, new BigDecimal("1000.00"), Currency.getInstance("CNY"),
                3, ApplicationStatus.SUBMITTED, now, now, 0);
    }

    private static LoanProduct product() {
        return new LoanProduct("CASH_LOAN_DEMO", 1, Currency.getInstance("CNY"),
                new BigDecimal("1000.00"), new BigDecimal("50000.00"), Set.of(3, 6, 12),
                new BigDecimal("12.000000"));
    }

    private static Loan loan() {
        Instant created = Instant.now();
        return new Loan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("1000.00"), Currency.getInstance("CNY"),
                new BigDecimal("12.000000"), 3, "CASH_LOAN_DEMO", 1,
                LoanStatus.PENDING_DISBURSEMENT, null, null, null, created, 0);
    }
}
