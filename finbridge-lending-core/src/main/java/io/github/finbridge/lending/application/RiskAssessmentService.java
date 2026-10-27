package io.github.finbridge.lending.application;

import io.github.finbridge.lending.domain.AssessmentDecision;
import io.github.finbridge.lending.domain.AssessmentStatus;
import io.github.finbridge.lending.domain.RiskAssessment;
import io.github.finbridge.lending.persistence.OriginationWorkflow;
import io.github.finbridge.lending.persistence.OriginationWorkflowRepository;
import io.github.finbridge.lending.persistence.OriginationWorkflowState;
import io.github.finbridge.lending.persistence.RiskAssessmentRepository;
import io.github.finbridge.risk.RiskDataGateway;
import io.github.finbridge.risk.RiskProviderOutcome;
import io.github.finbridge.risk.SyntheticRiskDecisionPolicy;
import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.spi.risk.RiskDataRequest;
import io.github.finbridge.spi.risk.RiskDataResult;
import io.github.finbridge.spi.risk.RiskDataType;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RiskAssessmentService {
    private final LendingApplicationService applications;
    private final OriginationWorkflowRepository workflows;
    private final RiskAssessmentRepository assessments;
    private final RiskDataGateway riskGateway;
    private final SyntheticRiskDecisionPolicy policy;
    private final Clock clock;

    public RiskAssessmentService(LendingApplicationService applications,
            OriginationWorkflowRepository workflows, RiskAssessmentRepository assessments,
            RiskDataGateway riskGateway, SyntheticRiskDecisionPolicy policy, Clock clock) {
        this.applications = applications;
        this.workflows = workflows;
        this.assessments = assessments;
        this.riskGateway = riskGateway;
        this.policy = policy;
        this.clock = clock;
    }

    public RiskAssessment evaluate(UUID tenantId, UUID applicationId, String correlationId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(correlationId, "correlationId");
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        return evaluateApplication(tenantId, applicationId, correlationId);
    }

    public RiskAssessment getLatest(UUID tenantId, UUID applicationId) {
        return assessments.findLatest(tenantId, applicationId)
                .orElseThrow(() -> new LendingCoreException("Risk assessment was not found"));
    }

    public WorkflowRecoverySummary recoverInterruptedWorkflows() {
        List<OriginationWorkflowRepository.WorkflowRecoveryCandidate> candidates =
                workflows.interruptedRiskWorkflows();
        int recovered = 0;
        int failures = 0;
        for (var candidate : candidates) {
            try {
                evaluate(candidate.tenantId(), candidate.applicationId(), "startup-risk-recovery");
                recovered++;
            } catch (RuntimeException unavailableDuringRecovery) {
                failures++;
            }
        }
        return new WorkflowRecoverySummary(candidates.size(), recovered, failures);
    }

    public record WorkflowRecoverySummary(int candidates, int recovered, int failures) { }

    private RiskAssessment evaluateApplication(UUID tenantId, UUID applicationId,
                                               String correlationId) {
        OriginationWorkflow workflow = beginOrResume(tenantId, applicationId);
        if (workflow.state() == OriginationWorkflowState.RISK_COMPLETED) {
            return assessments.findById(tenantId, workflow.completedAssessmentId())
                    .orElseThrow(() -> new LendingCoreException("Completed risk assessment was not found"));
        }
        var application = applications.getApplication(tenantId, applicationId);
        return evaluatePending(application, workflow, correlationId);
    }

    private RiskAssessment evaluatePending(io.github.finbridge.lending.domain.LoanApplication application,
                                           OriginationWorkflow workflow, String correlationId) {
        UUID assessmentId = workflow.activeAssessmentId() == null
                ? UUID.randomUUID() : workflow.activeAssessmentId();
        RiskAssessmentRepository.RiskAssessmentClaim claim = claimAssessment(
                application, workflow, assessmentId);
        if (claim.completed()) {
            return claim.assessment();
        }
        RiskDataRequest request = new RiskDataRequest(assessmentId, application.tenantId(),
                application.applicantId());
        Map<RiskDataType, RiskProviderOutcome> outcomes = new java.util.EnumMap<>(RiskDataType.class);
        for (RiskDataType type : RiskDataType.values()) {
            UUID executionId = UUID.randomUUID();
            RiskProviderOutcome outcome = riskGateway.query(application.tenantId(), type,
                    request,
                    new io.github.finbridge.spi.OperationContext(executionId, correlationId,
                            clock.instant().plusSeconds(2)));
            outcomes.put(type, outcome);
        }
        List<RiskAssessment.ProviderExecutionReference> providerReferences = providerReferences(outcomes);
        Map<RiskDataType, RiskDataResult> data = normalizedResults(outcomes);
        SyntheticRiskDecisionPolicy.Decision decision = evaluatePolicy(data);
        Instant completedAt = clock.instant();
        AssessmentDecision lendingDecision = AssessmentDecision.valueOf(decision.decision().name());
        RiskAssessment assessment = new RiskAssessment(assessmentId, application.applicationId(),
                application.tenantId(), AssessmentStatus.COMPLETED, decision.creditScore(),
                decision.blacklistHit(), decision.fraudSignal(), lendingDecision,
                decision.reasonCodes(), providerReferences, decision.policyVersion(),
                claim.assessment().createdAt(), completedAt);
        return completeAssessment(assessment, lendingDecision, workflow, application.version(), completedAt);
    }

    private RiskAssessmentRepository.RiskAssessmentClaim claimAssessment(
            io.github.finbridge.lending.domain.LoanApplication application,
            OriginationWorkflow workflow, UUID assessmentId) {
        return assessments.claimAssessment(application.tenantId(), application.applicationId(),
                assessmentId, workflow.workflowId(), workflow.activeRiskAttemptId(), workflow.version(),
                List.of(), clock.instant());
    }

    private RiskAssessment completeAssessment(RiskAssessment assessment,
            AssessmentDecision decision,
            OriginationWorkflow workflow, long applicationVersion, Instant completedAt) {
        return assessments.completeAndRecord(assessment, decision, workflow.activeRiskAttemptId(),
                applicationVersion, workflow.version(), completedAt);
    }

    private OriginationWorkflow beginOrResume(UUID tenantId, UUID applicationId) {
        return workflows.findByApplication(tenantId, applicationId)
                .map(workflow -> workflow.state() == OriginationWorkflowState.RISK_RUNNING
                        ? workflows.resumeRisk(tenantId, applicationId, clock.instant()) : workflow)
                .orElseGet(() -> workflows.startRisk(tenantId, applicationId, clock.instant()));
    }

    private SyntheticRiskDecisionPolicy.Decision evaluatePolicy(
            Map<RiskDataType, RiskDataResult> data) {
        return policy.evaluate(Map.copyOf(data));
    }

    private static List<RiskAssessment.ProviderExecutionReference> providerReferences(
            Map<RiskDataType, RiskProviderOutcome> outcomes) {
        List<RiskAssessment.ProviderExecutionReference> references = new ArrayList<>();
        outcomes.forEach((type, outcome) -> {
            PluginKey key = outcome.pluginKey();
            if (key != null) {
                references.add(new RiskAssessment.ProviderExecutionReference(outcome.executionId(),
                        key.pluginId(), key.pluginVersion(), type.name()));
            }
        });
        return List.copyOf(references);
    }

    private static Map<RiskDataType, RiskDataResult> normalizedResults(
            Map<RiskDataType, RiskProviderOutcome> outcomes) {
        Map<RiskDataType, RiskDataResult> results = new java.util.EnumMap<>(RiskDataType.class);
        for (RiskDataType type : RiskDataType.values()) {
            RiskProviderOutcome outcome = outcomes.get(type);
            RiskDataResult result = outcome == null ? null : outcome.result();
            if (result == null) {
                result = RiskDataResult.unavailable(type,
                        outcome == null ? "CAPABILITY_UNAVAILABLE" : outcome.failureCode());
            } else if (result.dataType() != type) {
                result = RiskDataResult.invalid(type, "DATA_TYPE_MISMATCH");
            }
            results.put(type, result);
        }
        return results;
    }
}
