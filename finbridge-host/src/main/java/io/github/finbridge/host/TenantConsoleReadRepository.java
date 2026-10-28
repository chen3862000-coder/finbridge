package io.github.finbridge.host;

import io.github.finbridge.lending.application.LendingCoreException;
import io.github.finbridge.lending.domain.Loan;
import io.github.finbridge.lending.domain.LoanProduct;
import io.github.finbridge.lending.domain.RepaymentPlan;
import io.github.finbridge.lending.domain.RiskAssessment;
import io.github.finbridge.lending.persistence.LendingAggregateRepository;
import io.github.finbridge.lending.persistence.LendingAggregateRepository.DisbursementClaim;
import io.github.finbridge.lending.persistence.LoanProductRepository;
import io.github.finbridge.lending.persistence.RiskAssessmentRepository;
import io.github.finbridge.lending.persistence.OriginationWorkflow;
import io.github.finbridge.lending.persistence.OriginationWorkflowRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class TenantConsoleReadRepository {
    private final LendingAggregateRepository aggregates;
    private final LoanProductRepository products;
    private final RiskAssessmentRepository assessments;

    public TenantConsoleReadRepository(LendingAggregateRepository aggregates,
            LoanProductRepository products, RiskAssessmentRepository assessments) {
        this.aggregates = aggregates;
        this.products = products;
        this.assessments = assessments;
    }

    public List<LoanProduct> products() {
        return products.findAllActive();
    }

    public RepaymentPlan repaymentPlan(UUID tenantId, UUID loanId) {
        loan(tenantId, loanId);
        return aggregates.findRepaymentPlan(tenantId, loanId)
                .orElseThrow(() -> new LendingCoreException("Repayment plan was not found"));
    }

    public Optional<Loan> loanForApplication(UUID tenantId, UUID applicationId) {
        return aggregates.findLoanByApplication(tenantId, applicationId);
    }

    public List<RiskAssessment> assessments(UUID tenantId, UUID applicationId) {
        return assessments.findAll(tenantId, applicationId);
    }

    public OriginationWorkflow workflow(UUID tenantId, UUID applicationId) {
        return aggregates.findByApplication(tenantId, applicationId)
                .orElseThrow(() -> new LendingCoreException("Origination workflow was not found"));
    }

    public List<OriginationWorkflowRepository.WorkflowStepView> workflowSteps(UUID tenantId,
            UUID applicationId) {
        return aggregates.findSteps(tenantId, applicationId);
    }

    public Optional<DisbursementClaim> payment(UUID tenantId, UUID loanId) {
        loan(tenantId, loanId);
        return aggregates.findDisbursement(tenantId, loanId);
    }

    private Loan loan(UUID tenantId, UUID loanId) {
        return aggregates.findLoan(tenantId, loanId)
                .orElseThrow(() -> new LendingCoreException("Loan was not found"));
    }
}
