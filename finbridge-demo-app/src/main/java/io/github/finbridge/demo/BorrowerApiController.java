package io.github.finbridge.demo;

import io.github.finbridge.host.TenantLendingFacade;
import io.github.finbridge.lending.domain.LoanApplication;
import io.github.finbridge.lending.domain.LoanProduct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Local-demo HTTP API; borrower ownership and tenant scope are enforced by the host facade. */
@RestController
@Validated
@RequestMapping("/api/borrower")
public class BorrowerApiController {
    private final TenantLendingFacade lending;

    public BorrowerApiController(TenantLendingFacade lending) {
        this.lending = lending;
    }

    /** Returns products available to the authenticated synthetic tenant. */
    @GetMapping("/products")
    public List<LoanProduct> products() {
        return lending.products();
    }

    /** Lists applications belonging to the current borrower identity. */
    @GetMapping("/applications")
    public List<LoanApplication> applications() {
        return lending.listApplicationsForCurrentBorrower(100);
    }

    /** Creates an application using an exact JSON decimal and a required idempotency key. */
    @PostMapping("/applications")
    @ResponseStatus(HttpStatus.CREATED)
    public LoanApplication apply(@Valid @RequestBody ApplicationRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        return lending.applyForCurrentTenant("self", request.productCode(), request.amount(),
                Currency.getInstance(request.currency()), request.termMonths(), idempotencyKey);
    }

    /** Returns application, risk, workflow, and loan details for this borrower's application. */
    @GetMapping("/applications/{applicationId}")
    public ApplicationStatus status(@PathVariable UUID applicationId) {
        var application = lending.borrowerApplication(applicationId);
        var assessments = lending.assessments(applicationId).stream()
                .map(assessment -> new RiskStatus(assessment.status().name(),
                        assessment.decision() == null ? null : assessment.decision().name(),
                        assessment.policyVersion(), assessment.completedAt(), assessment.creditScore(),
                        assessment.blacklistHit(), assessment.fraudSignal(), assessment.reasonCodes()))
                .toList();
        return new ApplicationStatus(application, assessments,
                lending.loanForApplication(applicationId).orElse(null), lending.workflow(applicationId),
                lending.workflowSteps(applicationId));
    }

    /** Returns the loan for an owned application, or null until one has been originated. */
    @GetMapping("/applications/{applicationId}/loan")
    public Object applicationLoan(@PathVariable UUID applicationId) {
        lending.borrowerApplication(applicationId);
        return lending.loanForApplication(applicationId).orElse(null);
    }

    /** Evaluates risk for an owned application using a required correlation ID. */
    @PostMapping("/applications/{applicationId}/evaluate")
    public Object evaluate(@PathVariable UUID applicationId,
            @RequestHeader("X-Correlation-Id") @NotBlank String correlationId) {
        var assessment = lending.evaluateForCurrentBorrower(applicationId, correlationId);
        return new RiskStatus(assessment.status().name(),
                assessment.decision() == null ? null : assessment.decision().name(),
                assessment.policyVersion(), assessment.completedAt(), assessment.creditScore(),
                assessment.blacklistHit(), assessment.fraudSignal(), assessment.reasonCodes());
    }

    /** Originates a pending loan for an owned approved application. */
    @PostMapping("/applications/{applicationId}/originate")
    public Object originate(@PathVariable UUID applicationId) {
        return lending.originateForCurrentBorrower(applicationId);
    }

    /** Returns a loan only when its originating application belongs to the caller. */
    @GetMapping("/loans/{loanId}")
    public Object loan(@PathVariable UUID loanId) {
        return lending.borrowerLoan(loanId);
    }

    /** Returns the repayment plan for a caller-owned loan. */
    @GetMapping("/loans/{loanId}/repayment-plan")
    public Object repaymentPlan(@PathVariable UUID loanId) {
        return lending.borrowerRepaymentPlan(loanId);
    }

    /** Returns payment state for a caller-owned loan when a disbursement exists. */
    @GetMapping("/loans/{loanId}/payment")
    public Object payment(@PathVariable UUID loanId) {
        return lending.borrowerPaymentIfPresent(loanId).orElse(null);
    }

    /** Dispatches a synthetic payment with required idempotency and correlation keys. */
    @PostMapping("/loans/{loanId}/disburse")
    public Object disburse(@PathVariable UUID loanId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @RequestHeader("X-Correlation-Id") @NotBlank String correlationId) {
        return lending.dispatchForCurrentBorrower(loanId, idempotencyKey, correlationId);
    }

    /** Queries the original synthetic payment provider for an owned loan. */
    @PostMapping("/loans/{loanId}/reconcile")
    public Object reconcile(@PathVariable UUID loanId,
            @RequestHeader("X-Correlation-Id") @NotBlank String correlationId) {
        return lending.reconcileForCurrentBorrower(loanId, correlationId);
    }

    public record ApplicationRequest(@NotBlank String productCode,
            @DecimalMin("0.01") BigDecimal amount, @NotBlank String currency, @Positive int termMonths) { }

    public record ApplicationStatus(LoanApplication application, List<?> assessments, Object loan,
            Object workflow, List<?> steps) {
        public ApplicationStatus {
            assessments = List.copyOf(assessments);
            steps = List.copyOf(steps);
        }
    }

    public record RiskStatus(String status, String decision, String policyVersion,
            java.time.Instant completedAt, Integer creditScore, Boolean blacklistHit,
            String fraudSignal, List<String> reasonCodes) {
        public RiskStatus {
            reasonCodes = List.copyOf(reasonCodes);
        }
    }
}
