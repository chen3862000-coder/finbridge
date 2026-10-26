package io.github.finbridge.lending.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class RepaymentScheduleCalculator {
    private static final MathContext INTERMEDIATE = MathContext.DECIMAL128;
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final int MONEY_SCALE = 2;

    public List<RepaymentInstallment> calculate(UUID planId, BigDecimal principal,
                                                 BigDecimal annualInterestRatePercent,
                                                 int installmentCount) {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(annualInterestRatePercent, "annualInterestRatePercent");
        validateInputs(principal, annualInterestRatePercent, installmentCount);
        BigDecimal monthlyRate = annualInterestRatePercent
                .divide(ONE_HUNDRED, INTERMEDIATE).divide(TWELVE, INTERMEDIATE);
        BigDecimal scheduledPayment = scheduledPayment(principal, monthlyRate, installmentCount);
        validateAmortization(principal, monthlyRate, scheduledPayment);
        List<RepaymentInstallment> rows = new ArrayList<>(installmentCount);
        BigDecimal remaining = principal.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        for (int number = 1; number <= installmentCount; number++) {
            remaining = appendInstallment(planId, rows, remaining, monthlyRate, scheduledPayment,
                    number, installmentCount);
        }
        validateSchedule(rows, principal, installmentCount);
        return List.copyOf(rows);
    }

    private static void validateInputs(BigDecimal principal, BigDecimal annualRate, int count) {
        if (principal.signum() <= 0 || principal.scale() > MONEY_SCALE
                || annualRate.signum() < 0 || count <= 0) {
            throw new IllegalArgumentException("Principal, rate, or term is invalid");
        }
    }

    private static void validateAmortization(BigDecimal principal, BigDecimal monthlyRate,
                                             BigDecimal payment) {
        if (monthlyRate.signum() > 0) {
            BigDecimal firstInterest = principal.multiply(monthlyRate, INTERMEDIATE)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            if (payment.compareTo(firstInterest) <= 0) {
                throw new IllegalArgumentException("Payment does not amortize the principal");
            }
        }
    }

    private static BigDecimal scheduledPayment(BigDecimal principal, BigDecimal monthlyRate,
                                                int count) {
        if (monthlyRate.signum() == 0) {
            return principal.divide(BigDecimal.valueOf(count), MONEY_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal factor = BigDecimal.ONE.add(monthlyRate, INTERMEDIATE).pow(count, INTERMEDIATE);
        return principal.multiply(monthlyRate, INTERMEDIATE).multiply(factor, INTERMEDIATE)
                .divide(factor.subtract(BigDecimal.ONE, INTERMEDIATE), INTERMEDIATE)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal appendInstallment(UUID planId, List<RepaymentInstallment> rows,
                                                BigDecimal opening, BigDecimal monthlyRate,
                                                BigDecimal payment, int number, int count) {
        BigDecimal interest = opening.multiply(monthlyRate, INTERMEDIATE)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal principalPart = number == count ? opening
                : payment.subtract(interest).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (principalPart.signum() < 0 || principalPart.compareTo(opening) > 0) {
            throw new IllegalArgumentException("Product terms produce negative amortization");
        }
        BigDecimal remaining = opening.subtract(principalPart).setScale(MONEY_SCALE,
                RoundingMode.UNNECESSARY);
        BigDecimal total = principalPart.add(interest).setScale(MONEY_SCALE,
                RoundingMode.UNNECESSARY);
        rows.add(new RepaymentInstallment(UUID.randomUUID(), planId, number, null, opening,
                principalPart, interest, total, remaining, InstallmentStatus.SCHEDULED));
        return remaining;
    }

    private static void validateSchedule(List<RepaymentInstallment> rows, BigDecimal principal,
                                         int installmentCount) {
        BigDecimal principalTotal = rows.stream().map(RepaymentInstallment::principalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remaining = rows.getLast().remainingPrincipal();
        if (principalTotal.compareTo(principal) != 0 || remaining.signum() != 0
                || rows.size() != installmentCount) {
            throw new IllegalStateException("Generated repayment schedule does not reconcile");
        }
    }
}
