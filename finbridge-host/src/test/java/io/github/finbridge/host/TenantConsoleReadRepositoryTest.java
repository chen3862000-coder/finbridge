package io.github.finbridge.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.finbridge.lending.application.LendingCoreException;
import io.github.finbridge.lending.domain.Loan;
import io.github.finbridge.lending.domain.LoanProduct;
import io.github.finbridge.lending.domain.RepaymentPlan;
import io.github.finbridge.lending.domain.RiskAssessment;
import io.github.finbridge.lending.persistence.LendingAggregateRepository;
import io.github.finbridge.lending.persistence.LoanProductRepository;
import io.github.finbridge.lending.persistence.OriginationWorkflow;
import io.github.finbridge.lending.persistence.OriginationWorkflowRepository.WorkflowStepView;
import io.github.finbridge.lending.persistence.RiskAssessmentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantConsoleReadRepositoryTest {
    @Test
    void delegatesAllTenantProjectionQueriesAndReportsMissingLoanAndWorkflow() {
        UUID tenant = UUID.randomUUID();
        UUID loanId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        LendingAggregateRepository aggregates = mock(LendingAggregateRepository.class);
        LoanProductRepository products = mock(LoanProductRepository.class);
        RiskAssessmentRepository assessments = mock(RiskAssessmentRepository.class);
        TenantConsoleReadRepository repository = new TenantConsoleReadRepository(aggregates, products, assessments);
        Loan loan = mock(Loan.class);
        RepaymentPlan plan = mock(RepaymentPlan.class);
        LoanProduct product = mock(LoanProduct.class);
        RiskAssessment assessment = mock(RiskAssessment.class);
        OriginationWorkflow workflow = mock(OriginationWorkflow.class);
        WorkflowStepView step = new WorkflowStepView(UUID.randomUUID(), "RISK", "COMPLETED", 1,
                java.time.Instant.EPOCH, java.time.Instant.EPOCH, UUID.randomUUID(), null, null);
        var claim = mock(LendingAggregateRepository.DisbursementClaim.class);
        when(products.findAllActive()).thenReturn(List.of(product));
        when(aggregates.findLoan(tenant, loanId)).thenReturn(Optional.of(loan));
        when(aggregates.findRepaymentPlan(tenant, loanId)).thenReturn(Optional.of(plan));
        when(aggregates.findLoanByApplication(tenant, applicationId)).thenReturn(Optional.of(loan));
        when(assessments.findAll(tenant, applicationId)).thenReturn(List.of(assessment));
        when(aggregates.findByApplication(tenant, applicationId)).thenReturn(Optional.of(workflow));
        when(aggregates.findSteps(tenant, applicationId)).thenReturn(List.of(step));
        when(aggregates.findDisbursement(tenant, loanId)).thenReturn(Optional.of(claim));

        assertEquals(List.of(product), repository.products());
        assertEquals(plan, repository.repaymentPlan(tenant, loanId));
        assertEquals(Optional.of(loan), repository.loanForApplication(tenant, applicationId));
        assertEquals(List.of(assessment), repository.assessments(tenant, applicationId));
        assertEquals(workflow, repository.workflow(tenant, applicationId));
        assertEquals(List.of(step), repository.workflowSteps(tenant, applicationId));
        assertEquals(Optional.of(claim), repository.payment(tenant, loanId));

        when(aggregates.findLoan(tenant, loanId)).thenReturn(Optional.empty());
        assertThrows(LendingCoreException.class, () -> repository.repaymentPlan(tenant, loanId));
        assertThrows(LendingCoreException.class, () -> repository.payment(tenant, loanId));
        when(aggregates.findLoan(tenant, loanId)).thenReturn(Optional.of(loan));
        when(aggregates.findRepaymentPlan(tenant, loanId)).thenReturn(Optional.empty());
        assertThrows(LendingCoreException.class, () -> repository.repaymentPlan(tenant, loanId));
        when(aggregates.findByApplication(tenant, applicationId)).thenReturn(Optional.empty());
        assertThrows(LendingCoreException.class, () -> repository.workflow(tenant, applicationId));
    }
}
