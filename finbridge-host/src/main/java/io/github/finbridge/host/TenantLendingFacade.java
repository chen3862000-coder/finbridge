package io.github.finbridge.host;

import io.github.finbridge.lending.application.DisbursementService;
import io.github.finbridge.lending.application.LendingApplicationService;
import io.github.finbridge.lending.application.RiskAssessmentService;
import io.github.finbridge.lending.application.LendingCoreException;
import io.github.finbridge.lending.application.CreateLoanApplicationCommand;
import io.github.finbridge.lending.application.LoanOriginationService;
import io.github.finbridge.lending.domain.LoanApplication;
import io.github.finbridge.lending.domain.RiskAssessment;
import io.github.finbridge.lending.persistence.LendingAggregateRepository.DisbursementClaim;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TenantLendingFacade {
    private final LendingApplicationService applications;
    private final RiskAssessmentService risk;
    private final DisbursementService payments;
    private final LoanOriginationService origination;

    public TenantLendingFacade(LendingApplicationService applications, RiskAssessmentService risk,
            DisbursementService payments, LoanOriginationService origination) {
        this.applications = applications;
        this.risk = risk;
        this.payments = payments;
        this.origination = origination;
    }

    public LoanApplication createApplication(String applicantId, String productCode,
            java.math.BigDecimal amount, java.util.Currency currency, int termMonths, String idempotencyKey) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        return applications.createApplication(new CreateLoanApplicationCommand(principal.tenantId(),
                applicantId, productCode, amount, currency, termMonths, idempotencyKey));
    }

    public RiskAssessment evaluate(UUID applicationId, String correlationId) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        application(applicationId);
        return risk.evaluate(principal.tenantId(), applicationId, correlationId);
    }

    public io.github.finbridge.lending.domain.Loan originate(UUID applicationId) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        application(applicationId);
        return origination.originate(principal.tenantId(), applicationId);
    }

    public LoanApplication application(UUID applicationId) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        try {
            return applications.getApplication(principal.tenantId(), applicationId);
        } catch (LendingCoreException notFound) {
            throw new LendingCoreException("Loan application was not found", notFound);
        }
    }

    public RiskAssessment riskAssessment(UUID applicationId) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        try {
            return risk.getLatest(principal.tenantId(), applicationId);
        } catch (LendingCoreException notFound) {
            throw new LendingCoreException("Risk assessment was not found", notFound);
        }
    }

    public DisbursementClaim payment(UUID loanId) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        try {
            return payments.getStatus(principal.tenantId(), loanId);
        } catch (LendingCoreException notFound) {
            throw new LendingCoreException("Payment was not found", notFound);
        }
    }

    public DisbursementClaim reconcile(UUID loanId, String correlationId) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        try {
            return payments.reconcile(principal.tenantId(), loanId, correlationId);
        } catch (LendingCoreException notFound) {
            throw new LendingCoreException("Payment was not found", notFound);
        }
    }

    public DisbursementClaim dispatch(UUID loanId, String idempotencyKey, String correlationId) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        try {
            return payments.dispatch(principal.tenantId(), loanId, idempotencyKey, correlationId);
        } catch (LendingCoreException notFound) {
            throw new LendingCoreException("Loan was not found or is not eligible for disbursement", notFound);
        }
    }
}
