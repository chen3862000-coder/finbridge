package io.github.finbridge.lending.application;

import io.github.finbridge.lending.domain.ApplicationStatus;
import io.github.finbridge.lending.domain.Loan;
import io.github.finbridge.lending.domain.LoanApplication;
import io.github.finbridge.lending.domain.LoanProduct;
import io.github.finbridge.lending.domain.LoanStatus;
import io.github.finbridge.lending.domain.RepaymentPlan;
import io.github.finbridge.lending.domain.RepaymentPlanStatus;
import io.github.finbridge.lending.domain.RepaymentScheduleCalculator;
import io.github.finbridge.lending.persistence.LendingAggregateRepository;
import io.github.finbridge.lending.persistence.LoanApplicationLookup;
import io.github.finbridge.lending.persistence.LoanProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Creates one pending loan and repayment plan atomically for an approved application. */
@Service
public class LoanOriginationService {
    private final ApplicationLookupBoundary applications;
    private final LoanProductLookupBoundary products;
    private final LendingAggregateRepository aggregates;
    private final RepaymentScheduleCalculator schedules;
    private final Clock clock;

    public LoanOriginationService(LoanApplicationLookup applications,
                                  LoanProductRepository products,
                                  LendingAggregateRepository aggregates,
                                  RepaymentScheduleCalculator schedules, Clock clock) {
        this.applications = new ApplicationLookupBoundary(applications);
        this.products = new LoanProductLookupBoundary(products);
        this.aggregates = aggregates;
        this.schedules = schedules;
        this.clock = clock;
    }

    /**
     * Originates an approved application within its tenant; repeated calls return the existing loan.
     * The loan and plan remain pending until confirmed disbursement.
     */
    public Loan originate(UUID tenantId, UUID applicationId) {
        LoanApplication application = applications.find(tenantId, applicationId)
                .orElseThrow(() -> new LendingCoreException("Loan application was not found"));
        if (application.status() != ApplicationStatus.APPROVED) {
            throw new LendingCoreException("Only an approved application can be originated");
        }
        Loan existing = aggregates.findLoanByApplication(tenantId, applicationId).orElse(null);
        if (existing != null) {
            return existing;
        }
        LoanProduct product = products.findVersion(application.productCode(), application.productVersion())
                .orElseThrow(() -> new LendingCoreException("Application product version is unavailable"));
        Instant createdAt = clock.instant();
        UUID loanId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        Loan loan = new Loan(loanId, application.applicationId(), tenantId,
                application.requestedAmount(), application.currency(),
                product.annualInterestRatePercent(), application.termMonths(), product.productCode(),
                product.version(), LoanStatus.PENDING_DISBURSEMENT, null, null, null, createdAt, 0);
        var installments = schedules.calculate(planId, loan.principal(),
                loan.annualInterestRatePercent(), loan.termMonths());
        var totalInterest = installments.stream().map(row -> row.interestAmount())
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        RepaymentPlan plan = new RepaymentPlan(planId, loanId, tenantId, loan.principal(),
                totalInterest, loan.principal().add(totalInterest), loan.termMonths(),
                RepaymentPlanStatus.PENDING_ACTIVATION, createdAt, null, installments);
        return aggregates.originateApproved(loan, plan);
    }

    /** Returns a loan only within the supplied tenant. */
    public Loan getLoan(UUID tenantId, UUID loanId) {
        return aggregates.findLoan(tenantId, loanId)
                .orElseThrow(() -> new LendingCoreException("Loan was not found"));
    }

    /** Lists recent loans for one tenant. */
    public List<Loan> listLoans(UUID tenantId, int limit) {
        return aggregates.findRecentLoans(tenantId, limit);
    }

    /** Returns the persisted disbursement state for a tenant-owned loan. */
    public LendingAggregateRepository.DisbursementClaim getDisbursement(UUID tenantId, UUID loanId) {
        return aggregates.findDisbursement(tenantId, loanId)
                .orElseThrow(() -> new LendingCoreException("Disbursement was not found"));
    }

    private static final class ApplicationLookupBoundary implements LoanApplicationLookup {
        private final LoanApplicationLookup delegate;

        private ApplicationLookupBoundary(LoanApplicationLookup delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.util.Optional<LoanApplication> find(UUID tenantId, UUID applicationId) {
            return delegate.find(tenantId, applicationId);
        }

    }

    private static final class LoanProductLookupBoundary {
        private final LoanProductRepository delegate;

        private LoanProductLookupBoundary(LoanProductRepository delegate) {
            this.delegate = delegate;
        }

        private java.util.Optional<LoanProduct> findVersion(String productCode, int version) {
            return delegate.findVersion(productCode, version);
        }
    }
}
