package io.github.finbridge.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.finbridge.host.TenantLendingFacade;
import io.github.finbridge.lending.domain.ApplicationStatus;
import io.github.finbridge.lending.domain.AssessmentDecision;
import io.github.finbridge.lending.domain.AssessmentStatus;
import io.github.finbridge.lending.domain.LoanApplication;
import io.github.finbridge.lending.domain.RiskAssessment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class BorrowerApiControllerTest {
    private static final UUID APPLICATION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID LOAN_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private TenantLendingFacade lending;
    private BorrowerApiController controller;

    @BeforeEach
    void setUp() {
        lending = org.mockito.Mockito.mock(TenantLendingFacade.class);
        controller = new BorrowerApiController(lending);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void applicationEndpointsKeepExactAmountAndSanitizeRiskEvidence() {
        var application = application();
        var risk = new RiskAssessment(UUID.randomUUID(), APPLICATION_ID, application.tenantId(),
                AssessmentStatus.COMPLETED, 780, null, null, AssessmentDecision.APPROVE,
                List.of("SYNTHETIC_APPROVAL"), List.of(), "synthetic-v1", NOW, NOW);
        when(lending.listApplicationsForCurrentBorrower(100)).thenReturn(List.of(application));
        when(lending.borrowerApplication(APPLICATION_ID)).thenReturn(application);
        when(lending.assessments(APPLICATION_ID)).thenReturn(List.of(risk));
        when(lending.loanForApplication(APPLICATION_ID)).thenReturn(Optional.empty());
        when(lending.workflow(APPLICATION_ID)).thenReturn(null);
        when(lending.workflowSteps(APPLICATION_ID)).thenReturn(List.of());

        assertEquals(List.of(application), controller.applications());
        var status = controller.status(APPLICATION_ID);

        assertEquals("1200.00", status.application().requestedAmount().toPlainString());
        assertEquals("APPROVE", ((BorrowerApiController.RiskStatus) status.assessments().getFirst()).decision());
        assertNull(status.loan());
        verify(lending).borrowerApplication(APPLICATION_ID);
        verify(lending).listApplicationsForCurrentBorrower(100);
    }

    @Test
    void borrowerOperationsDelegateWithCallerSuppliedIdempotencyAndCorrelationKeys() {
        var request = new BorrowerApiController.ApplicationRequest(
                "CASH_LOAN_DEMO", new BigDecimal("1200.00"), "CNY", 3);
        var application = application();
        when(lending.evaluateForCurrentBorrower(APPLICATION_ID, "risk-correlation")).thenReturn(new RiskAssessment(
                UUID.randomUUID(), APPLICATION_ID, application.tenantId(), AssessmentStatus.COMPLETED,
                null, true, null, AssessmentDecision.REJECT, List.of("SYNTHETIC_REJECT"), List.of(),
                "synthetic-v1", NOW, NOW));

        controller.products();
        controller.apply(request, "application-key");
        controller.applicationLoan(APPLICATION_ID);
        controller.evaluate(APPLICATION_ID, "risk-correlation");
        when(lending.originateForCurrentBorrower(APPLICATION_ID)).thenReturn(null);
        when(lending.borrowerLoan(LOAN_ID)).thenReturn(null);
        when(lending.borrowerRepaymentPlan(LOAN_ID)).thenReturn(null);
        when(lending.borrowerPaymentIfPresent(LOAN_ID)).thenReturn(Optional.empty());
        when(lending.dispatchForCurrentBorrower(LOAN_ID, "payment-key", "payment-correlation"))
                .thenReturn(null);
        when(lending.reconcileForCurrentBorrower(LOAN_ID, "reconcile-correlation")).thenReturn(null);
        controller.originate(APPLICATION_ID);
        controller.loan(LOAN_ID);
        controller.repaymentPlan(LOAN_ID);
        controller.payment(LOAN_ID);
        controller.disburse(LOAN_ID, "payment-key", "payment-correlation");
        controller.reconcile(LOAN_ID, "reconcile-correlation");

        verify(lending).applyForCurrentTenant("self", "CASH_LOAN_DEMO", new BigDecimal("1200.00"),
                Currency.getInstance("CNY"), 3, "application-key");
        verify(lending).evaluateForCurrentBorrower(APPLICATION_ID, "risk-correlation");
        verify(lending).dispatchForCurrentBorrower(LOAN_ID, "payment-key", "payment-correlation");
        verify(lending).reconcileForCurrentBorrower(LOAN_ID, "reconcile-correlation");
        verify(lending).originateForCurrentBorrower(APPLICATION_ID);
        verify(lending).borrowerLoan(LOAN_ID);
        verify(lending).borrowerRepaymentPlan(LOAN_ID);
        verify(lending).borrowerPaymentIfPresent(LOAN_ID);
        verify(lending).loanForApplication(APPLICATION_ID);
    }

    private static LoanApplication application() {
        return new LoanApplication(APPLICATION_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000001"), "synthetic-applicant",
                "CASH_LOAN_DEMO", 1, new BigDecimal("1200.00"), Currency.getInstance("CNY"), 3,
                ApplicationStatus.APPROVED, NOW, NOW, 1);
    }
}
