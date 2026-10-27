package io.github.finbridge.lending.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.finbridge.lending.domain.ApplicationStatus;
import io.github.finbridge.lending.domain.AssessmentDecision;
import io.github.finbridge.lending.domain.AssessmentStatus;
import io.github.finbridge.lending.domain.LoanApplication;
import io.github.finbridge.lending.domain.RiskAssessment;
import io.github.finbridge.lending.persistence.OriginationWorkflow;
import io.github.finbridge.lending.persistence.OriginationWorkflowRepository;
import io.github.finbridge.lending.persistence.OriginationWorkflowState;
import io.github.finbridge.lending.persistence.RiskAssessmentRepository;
import io.github.finbridge.risk.RiskDataGateway;
import io.github.finbridge.risk.RiskProviderOutcome;
import io.github.finbridge.risk.SyntheticRiskDecisionPolicy;
import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.spi.risk.BlacklistValue;
import io.github.finbridge.spi.risk.CreditScoreValue;
import io.github.finbridge.spi.risk.RiskDataRequest;
import io.github.finbridge.spi.risk.RiskDataResult;
import io.github.finbridge.spi.risk.RiskDataType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RiskAssessmentServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void evaluatesUsingCorePolicyAndPersistsPinnedProviderVersions() {
        UUID tenant = UUID.randomUUID();
        LoanApplication application = application(tenant, ApplicationStatus.SUBMITTED, 0);
        OriginationWorkflow running = workflow(tenant, application.applicationId(), 0);
        LendingApplicationService applications = mock(LendingApplicationService.class);
        OriginationWorkflowRepository workflows = mock(OriginationWorkflowRepository.class);
        RiskAssessmentRepository assessments = mock(RiskAssessmentRepository.class);
        RiskDataGateway gateway = mock(RiskDataGateway.class);
        when(applications.getApplication(tenant, application.applicationId())).thenReturn(application);
        when(workflows.findByApplication(tenant, application.applicationId())).thenReturn(Optional.empty());
        when(workflows.startRisk(tenant, application.applicationId(), NOW)).thenReturn(running);
        mockGatewayResults(gateway, tenant);
        when(assessments.claimAssessment(org.mockito.ArgumentMatchers.eq(tenant),
                org.mockito.ArgumentMatchers.eq(application.applicationId()),
                org.mockito.ArgumentMatchers.eq(running.activeAssessmentId()),
                org.mockito.ArgumentMatchers.eq(running.workflowId()),
                org.mockito.ArgumentMatchers.eq(running.activeRiskAttemptId()),
                org.mockito.ArgumentMatchers.eq(running.version()),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> new RiskAssessmentRepository.RiskAssessmentClaim(
                        pending(application, invocation.getArgument(2)), false));
        when(assessments.completeAndRecord(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(AssessmentDecision.APPROVE),
                org.mockito.ArgumentMatchers.eq(running.activeRiskAttemptId()), org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        RiskAssessmentService service = service(applications, workflows, assessments, gateway);

        RiskAssessment result = service.evaluate(tenant, application.applicationId(), "corr-1");

        assertEquals(AssessmentDecision.APPROVE, result.decision());
        assertEquals(720, result.creditScore());
        assertEquals(Boolean.FALSE, result.blacklistHit());
        assertEquals(SyntheticRiskDecisionPolicy.VERSION, result.policyVersion());
        assertEquals(3, result.providers().size());
        assertEquals(ApplicationStatus.SUBMITTED, application.status());
        verify(applications).getApplication(tenant, application.applicationId());
    }

    @Test
    void retryResumesRiskStepAndReturnsPersistedCompletedAssessmentWithoutProviderCalls() {
        UUID tenant = UUID.randomUUID();
        LoanApplication application = application(tenant, ApplicationStatus.RISK_EVALUATING, 1);
        OriginationWorkflow running = workflow(tenant, application.applicationId(), 3);
        LendingApplicationService applications = mock(LendingApplicationService.class);
        OriginationWorkflowRepository workflows = mock(OriginationWorkflowRepository.class);
        RiskAssessmentRepository assessments = mock(RiskAssessmentRepository.class);
        RiskDataGateway gateway = mock(RiskDataGateway.class);
        UUID completedId = UUID.randomUUID();
        running = new OriginationWorkflow(running.workflowId(), running.applicationId(),
                running.tenantId(), running.state(), running.activeRiskAttemptId(),
                UUID.randomUUID(), null, running.version(), running.createdAt(), running.updatedAt());
        OriginationWorkflow completed = new OriginationWorkflow(running.workflowId(),
                application.applicationId(), tenant, OriginationWorkflowState.RISK_COMPLETED,
                null, null, completedId, 5, NOW, NOW);
        RiskAssessment result = completedAssessment(application, completedId);
        when(applications.getApplication(tenant, application.applicationId())).thenReturn(application);
        when(workflows.findByApplication(tenant, application.applicationId())).thenReturn(Optional.of(running));
        when(workflows.resumeRisk(tenant, application.applicationId(), NOW)).thenReturn(completed);
        when(assessments.findById(tenant, running.activeAssessmentId()))
                .thenReturn(Optional.of(pending(application, running.activeAssessmentId())));
        when(assessments.findById(tenant, completedId)).thenReturn(Optional.of(result));

        assertSame(result, service(applications, workflows, assessments, gateway)
                .evaluate(tenant, application.applicationId(), "corr-retry"));
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

    @Test
    void latestAssessmentLookupIsTenantScopedAndReportsMissing() {
        UUID tenant = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        LendingApplicationService applications = mock(LendingApplicationService.class);
        OriginationWorkflowRepository workflows = mock(OriginationWorkflowRepository.class);
        RiskAssessmentRepository assessments = mock(RiskAssessmentRepository.class);
        RiskDataGateway gateway = mock(RiskDataGateway.class);
        when(assessments.findLatest(tenant, applicationId)).thenReturn(Optional.empty());
        org.junit.jupiter.api.Assertions.assertThrows(LendingCoreException.class,
                () -> service(applications, workflows, assessments, gateway).getLatest(tenant, applicationId));
    }

    @Test
    void latestLookupReturnsTenantScopedPersistedAssessment() {
        UUID tenant = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        RiskAssessment expected = completedAssessment(
                application(tenant, ApplicationStatus.APPROVED, 1), UUID.randomUUID());
        LendingApplicationService applications = mock(LendingApplicationService.class);
        OriginationWorkflowRepository workflows = mock(OriginationWorkflowRepository.class);
        RiskAssessmentRepository assessments = mock(RiskAssessmentRepository.class);
        RiskDataGateway gateway = mock(RiskDataGateway.class);
        when(assessments.findLatest(tenant, applicationId)).thenReturn(Optional.of(expected));

        assertSame(expected, service(applications, workflows, assessments, gateway)
                .getLatest(tenant, applicationId));
        verify(assessments).findLatest(tenant, applicationId);
    }

    @Test
    void completedWorkflowReplaysAssessmentWithoutRestartingOrCallingProviders() {
        UUID tenant = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        UUID assessmentId = UUID.randomUUID();
        OriginationWorkflow completed = new OriginationWorkflow(UUID.randomUUID(), applicationId,
                tenant, OriginationWorkflowState.RISK_COMPLETED, null, null, assessmentId,
                2, NOW, NOW);
        RiskAssessment expected = completedAssessment(
                application(tenant, ApplicationStatus.APPROVED, 1), assessmentId);
        LendingApplicationService applications = mock(LendingApplicationService.class);
        OriginationWorkflowRepository workflows = mock(OriginationWorkflowRepository.class);
        RiskAssessmentRepository assessments = mock(RiskAssessmentRepository.class);
        RiskDataGateway gateway = mock(RiskDataGateway.class);
        when(workflows.findByApplication(tenant, applicationId)).thenReturn(Optional.of(completed));
        when(assessments.findById(tenant, assessmentId)).thenReturn(Optional.of(expected));

        assertSame(expected, service(applications, workflows, assessments, gateway)
                .evaluate(tenant, applicationId, "corr-replay"));
        verify(applications, org.mockito.Mockito.never()).getApplication(tenant, applicationId);
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

    @Test
    void completedWorkflowWithoutAssessmentFailsClosed() {
        UUID tenant = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        UUID assessmentId = UUID.randomUUID();
        OriginationWorkflow completed = new OriginationWorkflow(UUID.randomUUID(), applicationId,
                tenant, OriginationWorkflowState.RISK_COMPLETED, null, null, assessmentId,
                2, NOW, NOW);
        LendingApplicationService applications = mock(LendingApplicationService.class);
        OriginationWorkflowRepository workflows = mock(OriginationWorkflowRepository.class);
        RiskAssessmentRepository assessments = mock(RiskAssessmentRepository.class);
        RiskDataGateway gateway = mock(RiskDataGateway.class);
        when(workflows.findByApplication(tenant, applicationId)).thenReturn(Optional.of(completed));
        when(assessments.findById(tenant, assessmentId)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(LendingCoreException.class,
                () -> service(applications, workflows, assessments, gateway)
                        .evaluate(tenant, applicationId, "corr-missing-result"));
    }

    @Test
    void startsWorkflowWhenNoPersistedWorkflowExists() {
        UUID tenant = UUID.randomUUID();
        LoanApplication application = application(tenant, ApplicationStatus.SUBMITTED, 0);
        LendingApplicationService applications = mock(LendingApplicationService.class);
        OriginationWorkflowRepository workflows = mock(OriginationWorkflowRepository.class);
        RiskAssessmentRepository assessments = mock(RiskAssessmentRepository.class);
        RiskDataGateway gateway = mock(RiskDataGateway.class);
        OriginationWorkflow started = workflow(tenant, application.applicationId(), 0);
        RiskAssessment completed = completedAssessment(application, started.activeAssessmentId());
        when(applications.getApplication(tenant, application.applicationId())).thenReturn(application);
        when(workflows.findByApplication(tenant, application.applicationId())).thenReturn(Optional.empty());
        when(workflows.startRisk(tenant, application.applicationId(), NOW)).thenReturn(started);
        when(assessments.claimAssessment(org.mockito.ArgumentMatchers.eq(tenant),
                org.mockito.ArgumentMatchers.eq(application.applicationId()),
                org.mockito.ArgumentMatchers.eq(started.activeAssessmentId()),
                org.mockito.ArgumentMatchers.eq(started.workflowId()),
                org.mockito.ArgumentMatchers.eq(started.activeRiskAttemptId()),
                org.mockito.ArgumentMatchers.eq(started.version()),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RiskAssessmentRepository.RiskAssessmentClaim(completed, true));

        assertSame(completed, service(applications, workflows, assessments, gateway)
                .evaluate(tenant, application.applicationId(), "corr-start"));
    }

    private static RiskAssessmentService service(LendingApplicationService applications,
            OriginationWorkflowRepository workflows, RiskAssessmentRepository assessments,
            RiskDataGateway gateway) {
        return new RiskAssessmentService(applications, workflows, assessments, gateway,
                new SyntheticRiskDecisionPolicy(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static void mockGatewayResults(RiskDataGateway gateway, UUID tenant) {
        for (RiskDataType type : RiskDataType.values()) {
            RiskDataResult result = switch (type) {
                case CREDIT_SCORE -> RiskDataResult.available(new CreditScoreValue(720));
                case BLACKLIST -> RiskDataResult.available(new BlacklistValue(false));
                case FRAUD_SIGNAL -> RiskDataResult.unavailable(type, "OPTIONAL_TIMEOUT");
            };
            when(gateway.query(org.mockito.ArgumentMatchers.eq(tenant), org.mockito.ArgumentMatchers.eq(type),
                    org.mockito.ArgumentMatchers.any(RiskDataRequest.class),
                    org.mockito.ArgumentMatchers.any(OperationContext.class)))
                    .thenReturn(RiskProviderOutcome.success(UUID.randomUUID(),
                            new PluginKey(type.name().toLowerCase(java.util.Locale.ROOT), "1.0.0"), result,
                            Duration.ofMillis(5)));
        }
    }

    private static LoanApplication application(UUID tenant, ApplicationStatus status, long version) {
        return new LoanApplication(UUID.randomUUID(), tenant, "synthetic-applicant", "CASH_LOAN_DEMO",
                1, new java.math.BigDecimal("1000.00"), java.util.Currency.getInstance("CNY"),
                3, status, NOW, NOW, version);
    }

    private static OriginationWorkflow workflow(UUID tenant, UUID applicationId, long version) {
        return new OriginationWorkflow(UUID.randomUUID(), applicationId, tenant,
                OriginationWorkflowState.RISK_RUNNING, UUID.randomUUID(), UUID.randomUUID(), null,
                version, NOW, NOW);
    }

    private static RiskAssessment pending(LoanApplication application, UUID assessmentId) {
        return new RiskAssessment(assessmentId, application.applicationId(), application.tenantId(),
                AssessmentStatus.PENDING, null, null, null, null, List.of(), List.of(),
                SyntheticRiskDecisionPolicy.VERSION, NOW, null);
    }

    private static RiskAssessment completedAssessment(LoanApplication application, UUID assessmentId) {
        return new RiskAssessment(assessmentId, application.applicationId(), application.tenantId(),
                AssessmentStatus.COMPLETED, 720, false, "NONE", AssessmentDecision.APPROVE,
                List.of("MANDATORY_RISK_DATA_CLEAR"), List.of(), SyntheticRiskDecisionPolicy.VERSION,
                NOW, NOW);
    }
}
