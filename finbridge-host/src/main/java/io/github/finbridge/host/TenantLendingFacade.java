package io.github.finbridge.host;

import io.github.finbridge.lending.application.DisbursementService;
import io.github.finbridge.lending.application.LendingApplicationService;
import io.github.finbridge.lending.application.RiskAssessmentService;
import io.github.finbridge.lending.application.LendingCoreException;
import io.github.finbridge.lending.application.CreateLoanApplicationCommand;
import io.github.finbridge.lending.application.LoanOriginationService;
import io.github.finbridge.lending.domain.LoanApplication;
import io.github.finbridge.lending.domain.Loan;
import io.github.finbridge.lending.domain.LoanProduct;
import io.github.finbridge.lending.domain.RepaymentPlan;
import io.github.finbridge.lending.domain.RiskAssessment;
import io.github.finbridge.lending.persistence.LendingAggregateRepository.DisbursementClaim;
import io.github.finbridge.lending.persistence.OriginationWorkflow;
import io.github.finbridge.lending.persistence.OriginationWorkflowRepository.WorkflowStepView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TenantLendingFacade {
    private final LendingApplicationService applications;
    private final RiskAssessmentService risk;
    private final DisbursementService payments;
    private final LoanOriginationService origination;
    private final TenantConsoleReadRepository consoleReads;

    public TenantLendingFacade(LendingApplicationService applications, RiskAssessmentService risk,
            DisbursementService payments, LoanOriginationService origination,
            TenantConsoleReadRepository consoleReads) {
        this.applications = applications;
        this.risk = risk;
        this.payments = payments;
        this.origination = origination;
        this.consoleReads = consoleReads;
    }

    public List<LoanApplication> listApplications(int limit) {
        return applications.listApplications(HostAuthorization.tenantPrincipal().tenantId(), limit);
    }

    public List<LoanApplication> listApplicationsForCurrentTenant(int limit) {
        return listApplications(limit);
    }

    public LoanApplication applyForCurrentTenant(String applicantId, String productCode,
            java.math.BigDecimal amount, java.util.Currency currency, int termMonths, String idempotencyKey) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        String scopedApplicantId = "demo-" + principal.subject() + "-" + applicantId;
        return applications.createApplication(new CreateLoanApplicationCommand(principal.tenantId(),
                scopedApplicantId, productCode, amount, currency, termMonths, idempotencyKey));
    }

    public List<Loan> loans(int limit) {
        return origination.listLoans(HostAuthorization.tenantPrincipal().tenantId(), limit);
    }

    public Loan loan(UUID loanId) {
        return origination.getLoan(HostAuthorization.tenantPrincipal().tenantId(), loanId);
    }

    public RepaymentPlan repaymentPlan(UUID loanId) {
        UUID tenantId = HostAuthorization.tenantPrincipal().tenantId();
        return consoleReads.repaymentPlan(tenantId, loanId);
    }

    public List<LoanProduct> products() {
        HostAuthorization.tenantPrincipal();
        return consoleReads.products();
    }

    public List<RiskAssessment> assessments(UUID applicationId) {
        UUID tenantId = HostAuthorization.tenantPrincipal().tenantId();
        application(applicationId);
        return consoleReads.assessments(tenantId, applicationId);
    }

    public OriginationWorkflow workflow(UUID applicationId) {
        UUID tenantId = HostAuthorization.tenantPrincipal().tenantId();
        application(applicationId);
        return consoleReads.workflow(tenantId, applicationId);
    }

    public List<WorkflowStepView> workflowSteps(UUID applicationId) {
        UUID tenantId = HostAuthorization.tenantPrincipal().tenantId();
        application(applicationId);
        return consoleReads.workflowSteps(tenantId, applicationId);
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

    public Loan originate(UUID applicationId) {
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

    public Optional<DisbursementClaim> paymentIfPresent(UUID loanId) {
        UUID tenantId = HostAuthorization.tenantPrincipal().tenantId();
        return consoleReads.payment(tenantId, loanId);
    }

    public Optional<Loan> loanForApplication(UUID applicationId) {
        UUID tenantId = HostAuthorization.tenantPrincipal().tenantId();
        application(applicationId);
        return consoleReads.loanForApplication(tenantId, applicationId);
    }

}
