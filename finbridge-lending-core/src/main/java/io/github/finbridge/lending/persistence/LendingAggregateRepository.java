package io.github.finbridge.lending.persistence;

import io.github.finbridge.lending.domain.Loan;
import io.github.finbridge.lending.domain.RepaymentPlan;
import java.util.Optional;
import java.util.UUID;

public interface LendingAggregateRepository {
    Optional<Loan> findLoanByApplication(UUID tenantId, UUID applicationId);

    Optional<Loan> findLoan(UUID tenantId, UUID loanId);

    Optional<RepaymentPlan> findRepaymentPlan(UUID tenantId, UUID loanId);

    Loan originate(Loan loan, RepaymentPlan plan);
}
