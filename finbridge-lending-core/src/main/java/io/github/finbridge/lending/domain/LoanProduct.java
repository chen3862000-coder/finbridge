package io.github.finbridge.lending.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.Set;

public record LoanProduct(String productCode, int version, Currency currency,
                          BigDecimal minimumPrincipal, BigDecimal maximumPrincipal,
                          Set<Integer> allowedTermsMonths, BigDecimal annualInterestRatePercent) {
    public LoanProduct {
        Objects.requireNonNull(productCode, "productCode");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(minimumPrincipal, "minimumPrincipal");
        Objects.requireNonNull(maximumPrincipal, "maximumPrincipal");
        Objects.requireNonNull(allowedTermsMonths, "allowedTermsMonths");
        Objects.requireNonNull(annualInterestRatePercent, "annualInterestRatePercent");
        allowedTermsMonths = Set.copyOf(allowedTermsMonths);
        if (productCode.isBlank() || version < 1 || minimumPrincipal.signum() <= 0
                || maximumPrincipal.compareTo(minimumPrincipal) < 0
                || annualInterestRatePercent.signum() < 0 || allowedTermsMonths.isEmpty()
                || allowedTermsMonths.stream().anyMatch(term -> term <= 0)) {
            throw new IllegalArgumentException("Invalid loan product configuration");
        }
    }

    public void validate(BigDecimal principal, int termMonths, Currency requestedCurrency) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(requestedCurrency, "requestedCurrency");
        if (principal.compareTo(minimumPrincipal) < 0
                || principal.compareTo(maximumPrincipal) > 0) {
            throw new IllegalArgumentException("Requested amount is outside product limits");
        }
        if (!allowedTermsMonths.contains(termMonths)) {
            throw new IllegalArgumentException("Requested term is not supported by product");
        }
        if (!currency.equals(requestedCurrency)) {
            throw new IllegalArgumentException("Requested currency does not match product");
        }
        if (principal.scale() > currency.getDefaultFractionDigits()) {
            throw new IllegalArgumentException("Requested amount has excess currency precision");
        }
    }
}
