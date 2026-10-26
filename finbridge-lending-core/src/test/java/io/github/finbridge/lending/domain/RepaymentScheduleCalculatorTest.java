package io.github.finbridge.lending.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RepaymentScheduleCalculatorTest {
    private static final RepaymentScheduleCalculator CALCULATOR = new RepaymentScheduleCalculator();

    @Test
    void zeroInterestScheduleAdjustsFinalPaymentToReconcilePrincipal() {
        List<RepaymentInstallment> schedule = CALCULATOR.calculate(UUID.randomUUID(),
                new BigDecimal("1000.00"), BigDecimal.ZERO, 3);

        assertEquals(List.of(new BigDecimal("333.33"), new BigDecimal("333.33"),
                        new BigDecimal("333.34")),
                schedule.stream().map(RepaymentInstallment::totalAmount).toList());
        assertPrincipalReconciles(schedule, new BigDecimal("1000.00"));
    }

    @Test
    void amortizationUsesApprovedRoundingAndHasDeterministicTotals() {
        UUID firstPlan = UUID.randomUUID();
        UUID secondPlan = UUID.randomUUID();
        List<RepaymentInstallment> schedule = CALCULATOR.calculate(firstPlan,
                new BigDecimal("10000.00"), new BigDecimal("12.000000"), 12);
        List<RepaymentInstallment> repeated = CALCULATOR.calculate(secondPlan,
                new BigDecimal("10000.00"), new BigDecimal("12.000000"), 12);

        assertEquals(new BigDecimal("888.49"), schedule.getFirst().totalAmount());
        assertEquals(new BigDecimal("661.86"), sumInterest(schedule));
        assertEquals(schedule.stream().map(RepaymentInstallment::totalAmount).toList(),
                repeated.stream().map(RepaymentInstallment::totalAmount).toList());
        assertPrincipalReconciles(schedule, new BigDecimal("10000.00"));
    }

    @Test
    void rejectsInvalidPrincipalRateAndTerm() {
        assertThrows(IllegalArgumentException.class, () -> CALCULATOR.calculate(UUID.randomUUID(),
                BigDecimal.ZERO, BigDecimal.ONE, 3));
        assertThrows(IllegalArgumentException.class, () -> CALCULATOR.calculate(UUID.randomUUID(),
                new BigDecimal("100.001"), BigDecimal.ONE, 3));
        assertThrows(IllegalArgumentException.class, () -> CALCULATOR.calculate(UUID.randomUUID(),
                new BigDecimal("100.00"), new BigDecimal("-0.01"), 3));
        assertThrows(IllegalArgumentException.class, () -> CALCULATOR.calculate(UUID.randomUUID(),
                new BigDecimal("100.00"), BigDecimal.ONE, 0));
        assertThrows(NullPointerException.class,
                () -> CALCULATOR.calculate(null, new BigDecimal("100.00"), BigDecimal.ONE, 3));
        assertThrows(NullPointerException.class,
                () -> CALCULATOR.calculate(UUID.randomUUID(), null, BigDecimal.ONE, 3));
        assertThrows(NullPointerException.class,
                () -> CALCULATOR.calculate(UUID.randomUUID(), new BigDecimal("100.00"), null, 3));
    }

    @Test
    void rejectsNegativeAmortization() {
        assertThrows(IllegalArgumentException.class, () -> CALCULATOR.calculate(UUID.randomUUID(),
                new BigDecimal("1000.00"), new BigDecimal("24000.000000"), 6));
    }

    private static void assertPrincipalReconciles(List<RepaymentInstallment> schedule,
                                                 BigDecimal principal) {
        BigDecimal principalTotal = schedule.stream().map(RepaymentInstallment::principalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, principal.compareTo(principalTotal));
        assertEquals(BigDecimal.ZERO.setScale(2), schedule.getLast().remainingPrincipal());
        assertTrue(schedule.stream().allMatch(row -> row.remainingPrincipal().signum() >= 0));
    }

    private static BigDecimal sumInterest(List<RepaymentInstallment> schedule) {
        return schedule.stream().map(RepaymentInstallment::interestAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
