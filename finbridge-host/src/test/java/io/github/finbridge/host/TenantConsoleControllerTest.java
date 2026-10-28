package io.github.finbridge.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.finbridge.lending.domain.ApplicationStatus;
import io.github.finbridge.lending.domain.AssessmentStatus;
import io.github.finbridge.lending.domain.InstallmentStatus;
import io.github.finbridge.lending.domain.Loan;
import io.github.finbridge.lending.domain.LoanApplication;
import io.github.finbridge.lending.domain.LoanProduct;
import io.github.finbridge.lending.domain.LoanStatus;
import io.github.finbridge.lending.domain.PaymentTransactionStatus;
import io.github.finbridge.lending.domain.RepaymentInstallment;
import io.github.finbridge.lending.domain.RepaymentPlan;
import io.github.finbridge.lending.domain.RepaymentPlanStatus;
import io.github.finbridge.lending.domain.RiskAssessment;
import io.github.finbridge.lending.application.LendingCoreException;
import io.github.finbridge.lending.persistence.LendingAggregateRepository.DisbursementClaim;
import io.github.finbridge.lending.persistence.OriginationWorkflow;
import io.github.finbridge.lending.persistence.OriginationWorkflowState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class TenantConsoleControllerTest {
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantReadEndpointsExposePersistedScopedViews() {
        UUID tenantId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        UUID loanId = UUID.randomUUID();
        Instant now = Instant.parse("2026-03-01T10:00:00Z");
        var application = new LoanApplication(applicationId, tenantId, "synthetic-applicant",
                "CASH_LOAN_DEMO", 1, new BigDecimal("1200.00"), Currency.getInstance("CNY"),
                3, ApplicationStatus.SUBMITTED, now, now, 0);
        var loan = new Loan(loanId, applicationId, tenantId, new BigDecimal("1200.00"),
                Currency.getInstance("CNY"), new BigDecimal("12.000000"), 3,
                "CASH_LOAN_DEMO", 1, LoanStatus.PENDING_DISBURSEMENT, null, null, null, now, 0);
        UUID planId = UUID.randomUUID();
        var plan = new RepaymentPlan(planId, loanId, tenantId, new BigDecimal("1200.00"),
                new BigDecimal("24.00"), new BigDecimal("1224.00"), 1,
                RepaymentPlanStatus.PENDING_ACTIVATION, now, null,
                List.of(new RepaymentInstallment(UUID.randomUUID(), planId, 1, null,
                        new BigDecimal("1200.00"), new BigDecimal("1200.00"), new BigDecimal("24.00"),
                        new BigDecimal("1224.00"), BigDecimal.ZERO, InstallmentStatus.SCHEDULED)));
        var assessment = new RiskAssessment(UUID.randomUUID(), applicationId, tenantId,
                AssessmentStatus.COMPLETED, 780, false, "LOW", io.github.finbridge.lending.domain.AssessmentDecision.APPROVE,
                List.of("SYNTHETIC_APPROVAL"), List.of(), "demo-policy-1", now, now);
        var workflow = new OriginationWorkflow(UUID.randomUUID(), applicationId, tenantId,
                OriginationWorkflowState.PAYMENT_PENDING, null, null, assessment.assessmentId(), null,
                2, now, now);
        var step = new io.github.finbridge.lending.persistence.OriginationWorkflowRepository.WorkflowStepView(
                UUID.randomUUID(), "RISK", "COMPLETED", 1, now, now, assessment.assessmentId(), null, null);
        LoanProduct product = new LoanProduct("CASH_LOAN_DEMO", 1, Currency.getInstance("CNY"),
                new BigDecimal("1000.00"), new BigDecimal("50000.00"), Set.of(3, 6, 12),
                new BigDecimal("12.000000"));
        var applicationService = mock(io.github.finbridge.lending.application.LendingApplicationService.class);
        var riskService = mock(io.github.finbridge.lending.application.RiskAssessmentService.class);
        var paymentService = mock(io.github.finbridge.lending.application.DisbursementService.class);
        var originationService = mock(io.github.finbridge.lending.application.LoanOriginationService.class);
        var consoleReads = mock(TenantConsoleReadRepository.class);
        TenantLendingFacade facade = new TenantLendingFacade(applicationService, riskService,
                paymentService, originationService, consoleReads);
        TenantConsoleController controller = new TenantConsoleController(facade);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new FinBridgePrincipal("tenant-user", tenantId), "", List.of()));
        when(applicationService.listApplications(tenantId, 25)).thenReturn(List.of(application));
        when(applicationService.listApplications(tenantId, 100)).thenReturn(List.of(application));
        when(originationService.listLoans(tenantId, 25)).thenReturn(List.of(loan));
        when(originationService.listLoans(tenantId, 100)).thenReturn(List.of());
        when(consoleReads.products()).thenReturn(List.of(product));
        when(applicationService.getApplication(tenantId, applicationId)).thenReturn(application);
        when(consoleReads.assessments(tenantId, applicationId)).thenReturn(List.of(assessment));
        when(consoleReads.loanForApplication(tenantId, applicationId)).thenReturn(Optional.of(loan));
        when(originationService.getLoan(tenantId, loanId)).thenReturn(loan);
        when(consoleReads.repaymentPlan(tenantId, loanId)).thenReturn(plan);
        when(consoleReads.payment(tenantId, loanId)).thenReturn(Optional.empty());
        when(consoleReads.workflow(tenantId, applicationId)).thenReturn(workflow);
        when(consoleReads.workflowSteps(tenantId, applicationId)).thenReturn(List.of(step));

        assertEquals(1, controller.dashboard(100).applications());
        assertEquals(1, controller.products().size());
        assertEquals(List.of(application), controller.applications(25));
        assertEquals(application, controller.application(applicationId));
        assertEquals(List.of(assessment), controller.assessments(applicationId));
        assertEquals(loan, controller.loanForApplication(applicationId));
        assertEquals(List.of(loan), controller.loans(25));
        assertEquals(plan, controller.loan(loanId).repaymentPlan());
        assertEquals(plan, controller.repaymentPlan(loanId));
        assertEquals(List.of(step), controller.workflow(applicationId).steps());

        assertThrows(IllegalArgumentException.class, () -> controller.applications(101));
        verify(applicationService).listApplications(tenantId, 100);
        verify(applicationService, atLeastOnce())
                .getApplication(tenantId, applicationId);
    }

    @Test
    void tenantCommandsDelegateUsingAuthenticatedTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        UUID loanId = UUID.randomUUID();
        TenantLendingFacade facade = mock(TenantLendingFacade.class);
        TenantConsoleController controller = new TenantConsoleController(facade);
        LoanApplication application = mock(LoanApplication.class);
        RiskAssessment assessment = mock(RiskAssessment.class);
        Loan loan = mock(Loan.class);
        DisbursementClaim payment = mock(DisbursementClaim.class);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new FinBridgePrincipal("tenant-user", tenantId), "", List.of()));
        when(facade.createApplication(anyString(), anyString(), any(), any(), anyInt(), anyString()))
                .thenReturn(application);
        when(facade.evaluate(applicationId, "correlation")).thenReturn(assessment);
        when(facade.originate(applicationId)).thenReturn(loan);
        when(facade.dispatch(loanId, "payment-key", "correlation")).thenReturn(payment);
        when(facade.payment(loanId)).thenReturn(payment);
        when(facade.reconcile(loanId, "correlation")).thenReturn(payment);

        assertEquals(application, controller.createApplication(new TenantConsoleController.CreateApplicationRequest(
                "synthetic-applicant", "CASH_LOAN_DEMO", new BigDecimal("1200.00"), "CNY", 3), "key"));
        assertEquals(assessment, controller.evaluate(applicationId, "correlation"));
        assertEquals(loan, controller.originate(applicationId));
        assertEquals(payment, controller.dispatch(loanId, "payment-key", "correlation"));
        assertEquals(payment, controller.payment(loanId));
        assertEquals(payment, controller.reconcile(loanId, "correlation"));

        verify(facade).createApplication("synthetic-applicant", "CASH_LOAN_DEMO",
                new BigDecimal("1200.00"), Currency.getInstance("CNY"), 3, "key");
        verify(facade).evaluate(applicationId, "correlation");
        verify(facade).dispatch(loanId, "payment-key", "correlation");
    }

    @Test
    void dashboardCountsTenantRiskLoanAndAmbiguousPaymentStates() {
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.parse("2026-03-01T10:00:00Z");
        Loan active = loan(tenantId, LoanStatus.ACTIVE, now);
        Loan processing = loan(tenantId, LoanStatus.DISBURSEMENT_PROCESSING, now);
        Loan failed = loan(tenantId, LoanStatus.DISBURSEMENT_FAILED, now);
        Loan pending = loan(tenantId, LoanStatus.PENDING_DISBURSEMENT, now);
        TenantLendingFacade facade = mock(TenantLendingFacade.class);
        TenantConsoleController controller = new TenantConsoleController(facade);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new FinBridgePrincipal("tenant-user", tenantId), "", List.of()));
        LoanApplication submitted = application(tenantId, ApplicationStatus.SUBMITTED, now);
        LoanApplication review = application(tenantId, ApplicationStatus.MANUAL_REVIEW, now);
        DisbursementClaim unknownClaim = claim(PaymentTransactionStatus.UNKNOWN);
        DisbursementClaim processingClaim = claim(PaymentTransactionStatus.PROCESSING);
        DisbursementClaim failedClaim = claim(PaymentTransactionStatus.FAILED);
        when(facade.listApplications(100)).thenReturn(List.of(submitted, review));
        when(facade.loans(100)).thenReturn(List.of(active, processing, failed, pending));
        when(facade.paymentIfPresent(active.loanId())).thenReturn(Optional.of(unknownClaim));
        when(facade.paymentIfPresent(processing.loanId())).thenReturn(Optional.of(processingClaim));
        when(facade.paymentIfPresent(failed.loanId())).thenReturn(Optional.of(failedClaim));

        var dashboard = controller.dashboard(100);

        assertEquals(2, dashboard.applications());
        assertEquals(1, dashboard.manualReview());
        assertEquals(4, dashboard.loans());
        assertEquals(1, dashboard.activeLoans());
        assertEquals(2, dashboard.unresolvedPayments());
        assertEquals(1, dashboard.ambiguousPayments());
        verify(facade, org.mockito.Mockito.never()).paymentIfPresent(pending.loanId());
    }

    private static LoanApplication application(UUID tenant, ApplicationStatus status, Instant at) {
        return new LoanApplication(UUID.randomUUID(), tenant, "synthetic-applicant", "CASH_LOAN_DEMO", 1,
                new BigDecimal("1200.00"), Currency.getInstance("CNY"), 3, status, at, at, 0);
    }

    private static Loan loan(UUID tenant, LoanStatus status, Instant at) {
        UUID transactionId = status == LoanStatus.ACTIVE ? UUID.randomUUID() : null;
        return new Loan(UUID.randomUUID(), UUID.randomUUID(), tenant, new BigDecimal("1200.00"),
                Currency.getInstance("CNY"), new BigDecimal("12.000000"), 3, "CASH_LOAN_DEMO", 1,
                status, transactionId, transactionId == null ? null : at,
                transactionId == null ? null : at, at, 0);
    }

    private static DisbursementClaim claim(PaymentTransactionStatus status) {
        var payment = mock(io.github.finbridge.lending.domain.PaymentTransaction.class);
        when(payment.status()).thenReturn(status);
        DisbursementClaim claim = mock(DisbursementClaim.class);
        when(claim.transaction()).thenReturn(payment);
        return claim;
    }

    @Test
    void listLimitsAndUnauthenticatedCallsAreRejectedBeforeFacadeAccess() {
        TenantLendingFacade facade = mock(TenantLendingFacade.class);
        TenantConsoleController controller = new TenantConsoleController(facade);

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> controller.applications(25));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new FinBridgePrincipal("tenant-user", UUID.randomUUID()), "", List.of()));
        assertThrows(IllegalArgumentException.class, () -> controller.applications(0));
        org.mockito.Mockito.verifyNoInteractions(facade);
    }

    @Test
    void mapsHostErrorsToStableHttpProblems() {
        ConsoleApiExceptionHandler handler = new ConsoleApiExceptionHandler();
        assertProblem(handler.notFound(new LendingCoreException("missing")), 404, "NOT_FOUND");
        assertProblem(handler.forbidden(new org.springframework.security.access.AccessDeniedException("denied")),
                403, "ACCESS_DENIED");
        assertProblem(handler.badRequest(new IllegalArgumentException("invalid")), 400, "INVALID_REQUEST");
        assertProblem(handler.conflict(new IllegalStateException("stale")), 409, "CONFLICT");
        assertProblem(handler.validation(new IllegalArgumentException("bad enum")), 400, "INVALID_REQUEST");
    }

    private static void assertProblem(
            org.springframework.http.ResponseEntity<java.util.Map<String, String>> response,
            int status, String code) {
        assertEquals(status, response.getStatusCode().value());
        assertEquals(code, response.getBody().get("code"));
    }

}
