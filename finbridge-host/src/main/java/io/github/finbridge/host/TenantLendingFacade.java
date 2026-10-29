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

/** Tenant and borrower boundary that derives scope from the trusted Spring Security principal. */
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

    /** Lists applications belonging to the authenticated tenant. */
    public List<LoanApplication> listApplications(int limit) {
        return applications.listApplications(HostAuthorization.tenantPrincipal().tenantId(), limit);
    }

    /** Explicit alias used by the internal tenant-wide Console. */
    public List<LoanApplication> listApplicationsForCurrentTenant(int limit) {
        return listApplications(limit);
    }

    /** Lists tenant applications owned by the authenticated borrower identity. */
    public List<LoanApplication> listApplicationsForCurrentBorrower(int limit) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        String applicantId = borrowerApplicantId(principal);
        return applications.listApplications(principal.tenantId(), limit).stream()
                .filter(application -> applicantId.equals(application.applicantId()))
                .toList();
    }

    /** Creates a synthetic demo application using the authenticated tenant and caller's mapped applicant reference. */
    public LoanApplication applyForCurrentTenant(String applicantId, String productCode,
            java.math.BigDecimal amount, java.util.Currency currency, int termMonths, String idempotencyKey) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        String scopedApplicantId = "demo-" + principal.subject() + "-" + applicantId;
        return applications.createApplication(new CreateLoanApplicationCommand(principal.tenantId(),
                scopedApplicantId, productCode, amount, currency, termMonths, idempotencyKey));
    }

    /** Evaluates only an application owned by the current borrower. */
    public RiskAssessment evaluateForCurrentBorrower(UUID applicationId, String correlationId) {
        LoanApplication application = borrowerApplication(applicationId);
        return risk.evaluate(application.tenantId(), applicationId, correlationId);
    }

    /** Originates a loan only from an application owned by the current borrower. */
    public Loan originateForCurrentBorrower(UUID applicationId) {
        LoanApplication application = borrowerApplication(applicationId);
        return origination.originate(application.tenantId(), applicationId);
    }

    /** Returns an application only when both tenant and borrower ownership match. */
    public LoanApplication borrowerApplication(UUID applicationId) {
        LoanApplication application = application(applicationId);
        String applicantId = borrowerApplicantId(HostAuthorization.tenantPrincipal());
        if (!applicantId.equals(application.applicantId())) {
            throw new LendingCoreException("Loan application was not found");
        }
        return application;
    }

    /** Returns a loan only when its source application belongs to the current borrower. */
    public Loan borrowerLoan(UUID loanId) {
        Loan loan = loan(loanId);
        borrowerApplication(loan.applicationId());
        return loan;
    }

    /** Returns the repayment plan only for a loan owned by the current borrower. */
    public RepaymentPlan borrowerRepaymentPlan(UUID loanId) {
        borrowerLoan(loanId);
        return repaymentPlan(loanId);
    }

    /** Returns optional payment state only for a loan owned by the current borrower. */
    public Optional<DisbursementClaim> borrowerPaymentIfPresent(UUID loanId) {
        borrowerLoan(loanId);
        return paymentIfPresent(loanId);
    }

    /** Dispatches a payment only for a loan owned by the current borrower. */
    public DisbursementClaim dispatchForCurrentBorrower(UUID loanId, String idempotencyKey,
            String correlationId) {
        borrowerLoan(loanId);
        return dispatch(loanId, idempotencyKey, correlationId);
    }

    /** Reconciles a payment only for a loan owned by the current borrower. */
    public DisbursementClaim reconcileForCurrentBorrower(UUID loanId, String correlationId) {
        borrowerLoan(loanId);
        return reconcile(loanId, correlationId);
    }

    /** Lists loans belonging to the authenticated tenant. */
    public List<Loan> loans(int limit) {
        return origination.listLoans(HostAuthorization.tenantPrincipal().tenantId(), limit);
    }

    /** Returns a loan only within the authenticated tenant. */
    public Loan loan(UUID loanId) {
        return origination.getLoan(HostAuthorization.tenantPrincipal().tenantId(), loanId);
    }

    /** Returns the tenant-owned repayment plan used by the internal Console. */
    public RepaymentPlan repaymentPlan(UUID loanId) {
        UUID tenantId = HostAuthorization.tenantPrincipal().tenantId();
        return consoleReads.repaymentPlan(tenantId, loanId);
    }

    /** Returns available loan products after confirming an authenticated tenant principal exists. */
    public List<LoanProduct> products() {
        HostAuthorization.tenantPrincipal();
        return consoleReads.products();
    }

    /** Returns risk assessments only after validating application ownership by the authenticated tenant. */
    public List<RiskAssessment> assessments(UUID applicationId) {
        UUID tenantId = HostAuthorization.tenantPrincipal().tenantId();
        application(applicationId);
        return consoleReads.assessments(tenantId, applicationId);
    }

    /** Returns workflow state only after validating application ownership by the authenticated tenant. */
    public OriginationWorkflow workflow(UUID applicationId) {
        UUID tenantId = HostAuthorization.tenantPrincipal().tenantId();
        application(applicationId);
        return consoleReads.workflow(tenantId, applicationId);
    }

    /** Returns workflow steps only after validating application ownership by the authenticated tenant. */
    public List<WorkflowStepView> workflowSteps(UUID applicationId) {
        UUID tenantId = HostAuthorization.tenantPrincipal().tenantId();
        application(applicationId);
        return consoleReads.workflowSteps(tenantId, applicationId);
    }

    /** Creates an application for an authenticated tenant using the supplied synthetic applicant reference. */
    public LoanApplication createApplication(String applicantId, String productCode,
            java.math.BigDecimal amount, java.util.Currency currency, int termMonths, String idempotencyKey) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        return applications.createApplication(new CreateLoanApplicationCommand(principal.tenantId(),
                applicantId, productCode, amount, currency, termMonths, idempotencyKey));
    }

    /** Evaluates an application after confirming it belongs to the authenticated tenant. */
    public RiskAssessment evaluate(UUID applicationId, String correlationId) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        application(applicationId);
        return risk.evaluate(principal.tenantId(), applicationId, correlationId);
    }

    /** Originates a loan after confirming the application belongs to the authenticated tenant. */
    public Loan originate(UUID applicationId) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        application(applicationId);
        return origination.originate(principal.tenantId(), applicationId);
    }

    /** Returns an application only within the authenticated tenant. */
    public LoanApplication application(UUID applicationId) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        try {
            return applications.getApplication(principal.tenantId(), applicationId);
        } catch (LendingCoreException notFound) {
            throw new LendingCoreException("Loan application was not found", notFound);
        }
    }

    /** Returns the latest risk assessment only within the authenticated tenant. */
    public RiskAssessment riskAssessment(UUID applicationId) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        try {
            return risk.getLatest(principal.tenantId(), applicationId);
        } catch (LendingCoreException notFound) {
            throw new LendingCoreException("Risk assessment was not found", notFound);
        }
    }

    /** Returns payment status only within the authenticated tenant. */
    public DisbursementClaim payment(UUID loanId) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        try {
            return payments.getStatus(principal.tenantId(), loanId);
        } catch (LendingCoreException notFound) {
            throw new LendingCoreException("Payment was not found", notFound);
        }
    }

    /** Reconciles tenant-owned payment work through its immutable original provider owner. */
    public DisbursementClaim reconcile(UUID loanId, String correlationId) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        try {
            return payments.reconcile(principal.tenantId(), loanId, correlationId);
        } catch (LendingCoreException notFound) {
            throw new LendingCoreException("Payment was not found", notFound);
        }
    }

    /** Dispatches a tenant-owned payment using the supplied idempotency key. */
    public DisbursementClaim dispatch(UUID loanId, String idempotencyKey, String correlationId) {
        FinBridgePrincipal principal = HostAuthorization.tenantPrincipal();
        try {
            return payments.dispatch(principal.tenantId(), loanId, idempotencyKey, correlationId);
        } catch (LendingCoreException notFound) {
            throw new LendingCoreException("Loan was not found or is not eligible for disbursement", notFound);
        }
    }

    /** Returns optional payment data for the authenticated tenant's Console view. */
    public Optional<DisbursementClaim> paymentIfPresent(UUID loanId) {
        UUID tenantId = HostAuthorization.tenantPrincipal().tenantId();
        return consoleReads.payment(tenantId, loanId);
    }

    /** Returns an optional loan after confirming its application belongs to the authenticated tenant. */
    public Optional<Loan> loanForApplication(UUID applicationId) {
        UUID tenantId = HostAuthorization.tenantPrincipal().tenantId();
        application(applicationId);
        return consoleReads.loanForApplication(tenantId, applicationId);
    }

    private static String borrowerApplicantId(FinBridgePrincipal principal) {
        return "demo-" + principal.subject() + "-self";
    }

}
