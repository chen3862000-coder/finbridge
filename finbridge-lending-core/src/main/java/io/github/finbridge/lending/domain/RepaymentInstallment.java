package io.github.finbridge.lending.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record RepaymentInstallment(UUID installmentId, UUID planId, int installmentNumber,
                                   LocalDate dueDate, BigDecimal openingPrincipal,
                                   BigDecimal principalAmount, BigDecimal interestAmount,
                                   BigDecimal totalAmount, BigDecimal remainingPrincipal,
                                   InstallmentStatus status) {
    public RepaymentInstallment {
        Objects.requireNonNull(installmentId, "installmentId");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(openingPrincipal, "openingPrincipal");
        Objects.requireNonNull(principalAmount, "principalAmount");
        Objects.requireNonNull(interestAmount, "interestAmount");
        Objects.requireNonNull(totalAmount, "totalAmount");
        Objects.requireNonNull(remainingPrincipal, "remainingPrincipal");
        Objects.requireNonNull(status, "status");
        if (installmentNumber < 1 || openingPrincipal.signum() < 0
                || principalAmount.signum() < 0 || interestAmount.signum() < 0
                || remainingPrincipal.signum() < 0
                || totalAmount.compareTo(principalAmount.add(interestAmount)) != 0) {
            throw new IllegalArgumentException("Invalid repayment installment values");
        }
    }

    public RepaymentInstallment withDueDate(LocalDate date) {
        return new RepaymentInstallment(installmentId, planId, installmentNumber,
                Objects.requireNonNull(date, "date"), openingPrincipal, principalAmount,
                interestAmount, totalAmount, remainingPrincipal, status);
    }
}
