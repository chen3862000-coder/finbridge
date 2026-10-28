package io.github.finbridge.host;

import io.github.finbridge.lending.domain.LoanApplication;
import io.github.finbridge.lending.domain.RiskAssessment;
import io.github.finbridge.lending.persistence.LendingAggregateRepository.DisbursementClaim;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@Validated
@RequestMapping("/api/console")
public class TenantConsoleController {
    private final TenantLendingFacade tenant;

    public TenantConsoleController(TenantLendingFacade tenant) {
        this.tenant = tenant;
    }

    @GetMapping("/dashboard")
    public Dashboard dashboard(@RequestParam(defaultValue = "100") @jakarta.validation.constraints.Min(1)
            @jakarta.validation.constraints.Max(100) int limit) {
        requireTenant();
        requireLimit(limit);
        List<LoanApplication> applications = tenant.listApplications(limit);
        List<io.github.finbridge.lending.domain.Loan> loans = tenant.loans(limit);
        List<DisbursementClaim> payments = loans.stream()
                .filter(loan -> loan.status() == io.github.finbridge.lending.domain.LoanStatus.ACTIVE
                        || loan.status() == io.github.finbridge.lending.domain.LoanStatus.DISBURSEMENT_PROCESSING
                        || loan.status() == io.github.finbridge.lending.domain.LoanStatus.DISBURSEMENT_FAILED)
                .map(loan -> tenant.paymentIfPresent(loan.loanId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        return new Dashboard(applications.size(),
                applications.stream().filter(application -> application.status()
                        == io.github.finbridge.lending.domain.ApplicationStatus.MANUAL_REVIEW).count(),
                loans.size(), loans.stream().filter(loan -> loan.status()
                        == io.github.finbridge.lending.domain.LoanStatus.ACTIVE).count(),
                payments.stream().filter(payment -> payment.transaction().status().unresolved()).count(),
                payments.stream().filter(payment -> payment.transaction().status()
                        == io.github.finbridge.lending.domain.PaymentTransactionStatus.UNKNOWN
                        || payment.transaction().status()
                        == io.github.finbridge.lending.domain.PaymentTransactionStatus.RECONCILIATION_REQUIRED)
                        .count());
    }

    @GetMapping("/products")
    public List<io.github.finbridge.lending.domain.LoanProduct> products() {
        requireTenant();
        return tenant.products();
    }

    @GetMapping("/applications")
    public List<LoanApplication> applications(@RequestParam(defaultValue = "25")
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) int limit) {
        requireTenant();
        return tenant.listApplications(requireLimit(limit));
    }

    @PostMapping("/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public LoanApplication createApplication(@Valid @RequestBody CreateApplicationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        requireTenant();
        return tenant.createApplication(request.applicantId(), request.productCode(), request.amount(),
                java.util.Currency.getInstance(request.currency()), request.termMonths(), idempotencyKey);
    }

    @GetMapping("/applications/{applicationId}")
    public LoanApplication application(@PathVariable UUID applicationId) {
        requireTenant();
        return tenant.application(applicationId);
    }

    @GetMapping("/applications/{applicationId}/assessments")
    public List<RiskAssessment> assessments(@PathVariable UUID applicationId) {
        requireTenant();
        return tenant.assessments(applicationId);
    }

    @GetMapping("/applications/{applicationId}/loan")
    public io.github.finbridge.lending.domain.Loan loanForApplication(@PathVariable UUID applicationId) {
        requireTenant();
        return tenant.loanForApplication(applicationId).orElse(null);
    }

    @PostMapping("/applications/{applicationId}/evaluate")
    public RiskAssessment evaluate(@PathVariable UUID applicationId,
            @RequestHeader("X-Correlation-Id") @NotBlank String correlationId) {
        requireTenant();
        return tenant.evaluate(applicationId, correlationId);
    }

    @PostMapping("/applications/{applicationId}/originate")
    public io.github.finbridge.lending.domain.Loan originate(@PathVariable UUID applicationId) {
        requireTenant();
        return tenant.originate(applicationId);
    }

    @GetMapping("/loans")
    public List<io.github.finbridge.lending.domain.Loan> loans(
            @RequestParam(defaultValue = "25") @jakarta.validation.constraints.Min(1)
            @jakarta.validation.constraints.Max(100) int limit) {
        requireTenant();
        return tenant.loans(requireLimit(limit));
    }

    @GetMapping("/loans/{loanId}")
    public LoanDetail loan(@PathVariable UUID loanId) {
        requireTenant();
        var loan = tenant.loan(loanId);
        return new LoanDetail(loan, tenant.repaymentPlan(loanId), tenant.paymentIfPresent(loanId).orElse(null));
    }

    @GetMapping("/loans/{loanId}/repayment-plan")
    public io.github.finbridge.lending.domain.RepaymentPlan repaymentPlan(@PathVariable UUID loanId) {
        requireTenant();
        return tenant.repaymentPlan(loanId);
    }

    @PostMapping("/loans/{loanId}/disburse")
    public DisbursementClaim dispatch(@PathVariable UUID loanId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @RequestHeader("X-Correlation-Id") @NotBlank String correlationId) {
        requireTenant();
        return tenant.dispatch(loanId, idempotencyKey, correlationId);
    }

    @GetMapping("/loans/{loanId}/payment")
    public DisbursementClaim payment(@PathVariable UUID loanId) {
        requireTenant();
        return tenant.payment(loanId);
    }

    @PostMapping("/loans/{loanId}/reconcile")
    public DisbursementClaim reconcile(@PathVariable UUID loanId,
            @RequestHeader("X-Correlation-Id") @NotBlank String correlationId) {
        requireTenant();
        return tenant.reconcile(loanId, correlationId);
    }

    @GetMapping("/applications/{applicationId}/workflow")
    public WorkflowView workflow(@PathVariable UUID applicationId) {
        requireTenant();
        return new WorkflowView(tenant.workflow(applicationId), tenant.workflowSteps(applicationId));
    }

    private static int requireLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return limit;
    }

    private static void requireTenant() {
        HostAuthorization.tenantPrincipal();
    }

    public record Dashboard(long applications, long manualReview, long loans, long activeLoans,
            long unresolvedPayments, long ambiguousPayments) { }

    public record CreateApplicationRequest(@NotBlank String applicantId, @NotBlank String productCode,
            @NotNull @DecimalMin("0.01") BigDecimal amount, @NotBlank String currency,
            @Positive int termMonths) { }

    public record LoanDetail(io.github.finbridge.lending.domain.Loan loan,
            io.github.finbridge.lending.domain.RepaymentPlan repaymentPlan, DisbursementClaim payment) { }

    public record WorkflowView(io.github.finbridge.lending.persistence.OriginationWorkflow workflow,
            List<io.github.finbridge.lending.persistence.OriginationWorkflowRepository.WorkflowStepView> steps) {
        public WorkflowView {
            steps = List.copyOf(steps);
        }
    }
}
