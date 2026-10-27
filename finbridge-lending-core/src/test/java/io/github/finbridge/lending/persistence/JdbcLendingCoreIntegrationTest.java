package io.github.finbridge.lending.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.finbridge.lending.application.LendingCoreException;
import io.github.finbridge.lending.domain.ApplicationStatus;
import io.github.finbridge.lending.domain.AssessmentDecision;
import io.github.finbridge.lending.domain.AssessmentStatus;
import io.github.finbridge.lending.domain.InstallmentStatus;
import io.github.finbridge.lending.domain.Loan;
import io.github.finbridge.lending.domain.LoanApplication;
import io.github.finbridge.lending.domain.LoanStatus;
import io.github.finbridge.lending.domain.PaymentTransactionStatus;
import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.spi.payment.PaymentStatus;
import io.github.finbridge.lending.domain.RepaymentInstallment;
import io.github.finbridge.lending.domain.RepaymentPlan;
import io.github.finbridge.lending.domain.RepaymentPlanStatus;
import io.github.finbridge.lending.domain.RiskAssessment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.sql.Connection;
import java.sql.SQLException;
import org.mockito.Mockito;
import org.flywaydb.core.Flyway;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class JdbcLendingCoreIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"));

    private JdbcTemplate jdbc;
    private JdbcLoanApplicationRepository applicationRepository;
    private JdbcLoanProductRepository productRepository;
    private JdbcLendingAggregateRepository aggregateRepository;
    private JdbcRiskAssessmentRepository assessmentRepository;
    private UUID tenantId;
    private LoanApplication application;

    @BeforeEach
    void setUp() {
        org.springframework.jdbc.datasource.DriverManagerDataSource dataSource =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        applicationRepository = new JdbcLoanApplicationRepository(dataSource);
        productRepository = new JdbcLoanProductRepository(dataSource);
        aggregateRepository = new JdbcLendingAggregateRepository(dataSource);
        assessmentRepository = new JdbcRiskAssessmentRepository(dataSource, new ObjectMapper());
        tenantId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        jdbc.execute("TRUNCATE TABLE payment_transactions, repayment_installments, repayment_plans, "
                + "loans, origination_workflow_steps, origination_workflows, risk_assessments, "
                + "loan_applications CASCADE");
        jdbc.update("INSERT INTO tenants (tenant_id, tenant_code) VALUES (?, ?) "
                        + "ON CONFLICT (tenant_id) DO NOTHING", tenantId, "DEMO_ALPHA");
        jdbc.update("INSERT INTO loan_products (product_code, product_version, currency, "
                        + "minimum_principal, maximum_principal, allowed_terms_months, "
                        + "annual_interest_rate_percent, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (product_code, product_version) DO UPDATE SET currency = EXCLUDED.currency, "
                        + "minimum_principal = EXCLUDED.minimum_principal, "
                        + "maximum_principal = EXCLUDED.maximum_principal, "
                        + "allowed_terms_months = EXCLUDED.allowed_terms_months, "
                        + "annual_interest_rate_percent = EXCLUDED.annual_interest_rate_percent, "
                        + "active = EXCLUDED.active",
                "CASH_LOAN_DEMO", 1, "CNY", new BigDecimal("1000.00"),
                new BigDecimal("50000.00"), new Integer[]{3, 6, 12},
                new BigDecimal("12.000000"), true);
        application = application(tenantId, UUID.randomUUID(), 0, ApplicationStatus.SUBMITTED);
    }

    @Test
    void applicationInsertReplayIsIdempotentAndPayloadMismatchConflicts() {
        String fingerprint = "a".repeat(64);
        LoanApplication created = applicationRepository.insert(application, "create-key", fingerprint);
        LoanApplication replay = applicationRepository.insert(application(tenantId, UUID.randomUUID(), 0,
                ApplicationStatus.SUBMITTED), "create-key", fingerprint);
        assertEquals(created.applicationId(), replay.applicationId());
        assertThrows(LendingCoreException.class, () -> applicationRepository.insert(
                application(tenantId, UUID.randomUUID(), 0, ApplicationStatus.SUBMITTED),
                "create-key", "b".repeat(64)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM loan_applications", Integer.class));
    }

    @Test
    void concurrentSameKeyApplicationClaimsCreateOneRecord() throws Exception {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors
                .newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> applicationRepository.insert(application,
                    "concurrent-key", "c".repeat(64)));
            var secondApplication = application(tenantId, UUID.randomUUID(), 0,
                    ApplicationStatus.SUBMITTED);
            var second = executor.submit(() -> applicationRepository.insert(secondApplication,
                    "concurrent-key", "c".repeat(64)));
            assertEquals(first.get().applicationId(), second.get().applicationId());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM loan_applications", Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void tenantScopedApplicationLookupAndOptimisticUpdateWork() {
        applicationRepository.insert(application, "create-key", "d".repeat(64));
        assertTrue(applicationRepository.find(UUID.randomUUID(), application.applicationId()).isEmpty());
        LoanApplication evaluating = application.transitionTo(ApplicationStatus.RISK_EVALUATING,
                Instant.now());
        assertEquals(evaluating, applicationRepository.updateStatus(evaluating, 0));
        assertThrows(LendingCoreException.class,
                () -> applicationRepository.updateStatus(application, 0));
    }

    @Test
    void productRepositoryReadsVersionedTermsAndCurrency() {
        var product = productRepository.findActive("CASH_LOAN_DEMO").orElseThrow();
        var versionedProduct = productRepository.findVersion("CASH_LOAN_DEMO", 1).orElseThrow();
        assertEquals(1, product.version());
        assertEquals(product, versionedProduct);
        assertEquals(Currency.getInstance("CNY"), product.currency());
        assertEquals(List.of(3, 6, 12), product.allowedTermsMonths().stream().sorted().toList());
        assertTrue(productRepository.findActive("missing").isEmpty());
    }

    @Test
    void loanPlanAndInstallmentsPersistAtomicallyAndRemainTenantScoped() {
        applicationRepository.insert(application, "create-key", "e".repeat(64));
        Loan loan = loan(application);
        RepaymentPlan plan = plan(loan);
        aggregateRepository.originate(loan, plan);

        assertEquals(loan, aggregateRepository.findLoanByApplication(tenantId,
                application.applicationId()).orElseThrow());
        RepaymentPlan storedPlan = aggregateRepository.findRepaymentPlan(tenantId, loan.loanId())
                .orElseThrow();
        assertEquals(plan.planId(), storedPlan.planId());
        assertEquals(3, storedPlan.installments().size());
        assertTrue(aggregateRepository.findLoan(UUID.randomUUID(), loan.loanId()).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM repayment_installments "
                + "WHERE due_date IS NOT NULL", Integer.class));
    }

    @Test
    void duplicateOriginationDoesNotLeavePartialLoanOrPlan() {
        applicationRepository.insert(application, "create-key", "f".repeat(64));
        Loan first = loan(application);
        aggregateRepository.originate(first, plan(first));
        Loan second = loan(application);
        aggregateRepository.originate(second, plan(second));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM loans", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM repayment_plans", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM repayment_installments", Integer.class));
    }

    @Test
    void interruptedAggregateRecoveryRethrowsWhenPlanWasNotCommitted() {
        applicationRepository.insert(application, "partial-key", "i".repeat(64));
        Loan incomplete = loan(application);
        jdbc.update("INSERT INTO loans (loan_id, application_id, tenant_id, principal, currency, "
                        + "annual_interest_rate_percent, term_months, product_code, product_version, "
                        + "status, created_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                incomplete.loanId(), incomplete.applicationId(), incomplete.tenantId(),
                incomplete.principal(), incomplete.currency().getCurrencyCode(),
                incomplete.annualInterestRatePercent(), incomplete.termMonths(), incomplete.productCode(),
                incomplete.productVersion(), incomplete.status().name(),
                java.sql.Timestamp.from(incomplete.createdAt()), incomplete.version());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> aggregateRepository.originate(incomplete, plan(incomplete)));
        assertTrue(aggregateRepository.findRepaymentPlan(tenantId, incomplete.loanId()).isEmpty());
    }

    @Test
    void riskAssessmentPersistsNormalizedEvidenceAndProviderReferences() {
        applicationRepository.insert(application, "create-key", "g".repeat(64));
        var reference = new RiskAssessment.ProviderExecutionReference(UUID.randomUUID(),
                "creditscorex", "1.0.0", "CREDIT_SCORE");
        RiskAssessment assessment = new RiskAssessment(UUID.randomUUID(), application.applicationId(),
                tenantId, AssessmentStatus.COMPLETED, 720, false, "NONE", AssessmentDecision.APPROVE,
                List.of("SCORE_ACCEPTABLE"), List.of(reference), "demo-policy-1", Instant.now(), Instant.now());
        assessmentRepository.save(assessment);
        RiskAssessment loaded = assessmentRepository.findLatest(tenantId, application.applicationId())
                .orElseThrow();
        assertEquals(AssessmentDecision.APPROVE, loaded.decision());
        assertEquals("creditscorex", loaded.providers().getFirst().pluginId());
        assertEquals(List.of("SCORE_ACCEPTABLE"), loaded.reasonCodes());
    }

    @Test
    void durableRiskWorkflowClaimsAndCompletesAtomicallyWithDecision() {
        applicationRepository.insert(application, "workflow-key", "j".repeat(64));
        OriginationWorkflowRepository workflowRepository = aggregateRepository;
        Instant now = Instant.parse("2026-01-02T00:00:00Z");
        var workflow = workflowRepository.startRisk(tenantId, application.applicationId(), now);
        assertEquals(OriginationWorkflowState.RISK_RUNNING, workflow.state());
        assertEquals(ApplicationStatus.RISK_EVALUATING,
                applicationRepository.find(tenantId, application.applicationId()).orElseThrow().status());
        var claim = assessmentRepository.claimAssessment(tenantId, application.applicationId(),
                workflow.activeAssessmentId(), workflow.workflowId(), workflow.activeRiskAttemptId(),
                workflow.version(), List.of(), now);
        UUID assessmentId = claim.assessment().assessmentId();
        assertEquals(AssessmentStatus.PENDING, claim.assessment().status());
        var reference = new RiskAssessment.ProviderExecutionReference(UUID.randomUUID(),
                "creditscorex", "1.0.0", "CREDIT_SCORE");
        var completed = new RiskAssessment(assessmentId, application.applicationId(), tenantId,
                AssessmentStatus.COMPLETED, 720, false, "NONE", AssessmentDecision.APPROVE,
                List.of("MANDATORY_RISK_DATA_CLEAR"), List.of(reference), "demo-risk-policy-1",
                now, now.plusSeconds(1));
        RiskAssessment saved = assessmentRepository.completeAndRecord(completed,
                AssessmentDecision.APPROVE, workflow.activeRiskAttemptId(), 1, workflow.version(),
                now.plusSeconds(1));
        assertEquals(AssessmentDecision.APPROVE, saved.decision());
        assertEquals(ApplicationStatus.APPROVED,
                applicationRepository.find(tenantId, application.applicationId()).orElseThrow().status());
        assertEquals(OriginationWorkflowState.RISK_COMPLETED,
                workflowRepository.findByApplication(tenantId, application.applicationId()).orElseThrow().state());
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT state FROM origination_workflow_steps WHERE attempt_id = ?",
                String.class, workflow.activeRiskAttemptId()));
    }

    @Test
    void startRiskIsIdempotentWhenWorkflowWasCompletedConcurrently() {
        applicationRepository.insert(application, "workflow-start-replay-key", "o".repeat(64));
        Instant now = Instant.parse("2026-01-02T00:00:00Z");
        OriginationWorkflow started = aggregateRepository.startRisk(
                tenantId, application.applicationId(), now);
        var claim = assessmentRepository.claimAssessment(tenantId, application.applicationId(),
                started.activeAssessmentId(), started.workflowId(), started.activeRiskAttemptId(),
                started.version(), List.of(), now);
        RiskAssessment completed = new RiskAssessment(claim.assessment().assessmentId(),
                application.applicationId(), tenantId, AssessmentStatus.COMPLETED, 720, false, "NONE",
                AssessmentDecision.APPROVE, List.of("CLEAR"), List.of(), "demo-risk-policy-1",
                now, now.plusSeconds(1));
        assessmentRepository.completeAndRecord(completed, AssessmentDecision.APPROVE,
                started.activeRiskAttemptId(), 1, started.version(), now.plusSeconds(1));

        OriginationWorkflow replay = aggregateRepository.startRisk(
                tenantId, application.applicationId(), now.plusSeconds(2));

        assertEquals(OriginationWorkflowState.RISK_COMPLETED, replay.state());
        assertEquals(completed.assessmentId(), replay.completedAssessmentId());
    }

    @Test
    void workflowResumeRejectsMissingWorkflowAndUnsupportedState() {
        assertThrows(LendingCoreException.class, () -> aggregateRepository.resumeRisk(
                tenantId, UUID.randomUUID(), Instant.parse("2026-01-02T00:00:00Z")));
        applicationRepository.insert(application, "workflow-invalid-resume-key", "p".repeat(64));
        assertThrows(LendingCoreException.class, () -> aggregateRepository.resumeRisk(
                tenantId, application.applicationId(), Instant.parse("2026-01-02T00:00:00Z")));
    }

    @Test
    void startRiskRejectsMissingApplication() {
        assertThrows(LendingCoreException.class, () -> aggregateRepository.startRisk(
                tenantId, UUID.randomUUID(), Instant.parse("2026-01-02T00:00:00Z")));
    }

    @Test
    void rollbackFailureIsSuppressedOnOriginalFailure() throws SQLException {
        Connection connection = Mockito.mock(Connection.class);
        SQLException rollbackFailure = new SQLException("synthetic rollback failure");
        RuntimeException originalFailure = new RuntimeException("synthetic transaction failure");
        Mockito.doThrow(rollbackFailure).when(connection).rollback();

        JdbcRiskAssessmentRepository.rollback(connection, originalFailure);

        assertSame(rollbackFailure, originalFailure.getSuppressed()[0]);
    }

    @Test
    void repositoryRequiredResultGuardsFailClosed() {
        assertThrows(LendingCoreException.class,
                () -> JdbcLendingAggregateRepository.requireOne(0, "synthetic optimistic miss"));
        assertThrows(LendingCoreException.class,
                () -> JdbcDisbursementRepository.requireOne(0, "synthetic payment update miss"));
        assertThrows(LendingCoreException.class,
                () -> JdbcDisbursementRepository.requirePresent(
                        java.util.Optional.empty(), "synthetic required payment row"));
    }

    @Test
    void interruptedRiskStepCanBeRetriedWithoutLosingWorkflowHistory() {
        applicationRepository.insert(application, "workflow-retry-key", "k".repeat(64));
        OriginationWorkflowRepository workflowRepository = aggregateRepository;
        Instant now = Instant.parse("2026-01-02T00:00:00Z");
        var first = workflowRepository.startRisk(tenantId, application.applicationId(), now);
        assessmentRepository.claimAssessment(tenantId, application.applicationId(),
                first.activeAssessmentId(), first.workflowId(), first.activeRiskAttemptId(),
                first.version(), List.of(), now);
        var second = workflowRepository.resumeRisk(tenantId, application.applicationId(), now.plusSeconds(5));
        assertNotEquals(first.activeRiskAttemptId(), second.activeRiskAttemptId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM origination_workflow_steps "
                + "WHERE workflow_id = ? AND state = 'INTERRUPTED'", Integer.class, first.workflowId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM origination_workflow_steps "
                + "WHERE workflow_id = ?", Integer.class, first.workflowId()));
    }

    @Test
    void workflowCompletionRollsBackWhenOptimisticApplicationVersionIsStale() {
        applicationRepository.insert(application, "workflow-rollback-key", "l".repeat(64));
        OriginationWorkflowRepository workflowRepository = aggregateRepository;
        Instant now = Instant.parse("2026-01-02T00:00:00Z");
        var workflow = workflowRepository.startRisk(tenantId, application.applicationId(), now);
        UUID assessmentId = workflow.activeAssessmentId();
        assessmentRepository.claimAssessment(tenantId, application.applicationId(), assessmentId,
                workflow.workflowId(), workflow.activeRiskAttemptId(), workflow.version(), List.of(), now);
        var completed = new RiskAssessment(assessmentId, application.applicationId(), tenantId,
                AssessmentStatus.COMPLETED, 720, false, "NONE", AssessmentDecision.APPROVE,
                List.of("CLEAR"), List.of(), "demo-risk-policy-1", now, now.plusSeconds(1));

        assertThrows(LendingCoreException.class, () -> assessmentRepository.completeAndRecord(
                completed, AssessmentDecision.APPROVE, workflow.activeRiskAttemptId(), 999,
                workflow.version(), now.plusSeconds(1)));
        assertEquals(AssessmentStatus.PENDING,
                assessmentRepository.findById(tenantId, assessmentId).orElseThrow().status());
        assertEquals(OriginationWorkflowState.RISK_RUNNING,
                workflowRepository.findByApplication(tenantId, application.applicationId()).orElseThrow().state());
    }

    @Test
    void completedAssessmentClaimReplaysPersistedDecision() {
        applicationRepository.insert(application, "workflow-replay-key", "m".repeat(64));
        Instant now = Instant.parse("2026-01-02T00:00:00Z");
        OriginationWorkflow workflow = aggregateRepository.startRisk(
                tenantId, application.applicationId(), now);
        var claim = assessmentRepository.claimAssessment(tenantId, application.applicationId(),
                workflow.activeAssessmentId(), workflow.workflowId(), workflow.activeRiskAttemptId(),
                workflow.version(), List.of(), now);
        RiskAssessment completed = new RiskAssessment(claim.assessment().assessmentId(),
                application.applicationId(), tenantId, AssessmentStatus.COMPLETED, 720, false, "NONE",
                AssessmentDecision.APPROVE, List.of("CLEAR"), List.of(), "demo-risk-policy-1",
                now, now.plusSeconds(1));
        assessmentRepository.completeAndRecord(completed, AssessmentDecision.APPROVE,
                workflow.activeRiskAttemptId(), 1, workflow.version(), now.plusSeconds(1));

        var replay = assessmentRepository.claimAssessment(tenantId, application.applicationId(),
                workflow.activeAssessmentId(), workflow.workflowId(), workflow.activeRiskAttemptId(),
                workflow.version(), List.of(), now.plusSeconds(2));

        assertTrue(replay.completed());
        assertEquals(AssessmentDecision.APPROVE, replay.assessment().decision());
    }

    @Test
    void riskClaimRejectsStaleWorkflowAttempt() {
        applicationRepository.insert(application, "workflow-stale-claim-key", "n".repeat(64));
        Instant now = Instant.parse("2026-01-02T00:00:00Z");
        OriginationWorkflow workflow = aggregateRepository.startRisk(
                tenantId, application.applicationId(), now);

        assertThrows(LendingCoreException.class, () -> assessmentRepository.claimAssessment(
                tenantId, application.applicationId(), workflow.activeAssessmentId(),
                workflow.workflowId(), UUID.randomUUID(), workflow.version(), List.of(), now));
    }

    @Test
    void durableDisbursementPinsOwnerAndActivatesLoanPlanOnlyAfterPersistedSuccess() {
        Loan loan = prepareApprovedLoan("payment-application");
        Instant now = Instant.parse("2026-03-01T10:00:02Z");
        PluginKey owner = new PluginKey("alphapay", "1.0.0");
        String idempotencyKey = "disburse-success-key";
        String fingerprint = "a".repeat(64);
        var claim = aggregateRepository.claimDisbursement(tenantId, loan.loanId(), owner,
                UUID.randomUUID(), idempotencyKey, fingerprint, UUID.randomUUID(), now.plusSeconds(2));

        assertTrue(claim.dispatchClaimed());
        assertEquals(LoanStatus.DISBURSEMENT_PROCESSING, claim.loan().status());
        assertEquals(RepaymentPlanStatus.PENDING_ACTIVATION, claim.plan().status());
        assertTrue(aggregateRepository.hasUnresolvedPaymentOwner(owner));
        var replay = aggregateRepository.claimDisbursement(tenantId, loan.loanId(), owner,
                UUID.randomUUID(), idempotencyKey, fingerprint, UUID.randomUUID(), now.plusSeconds(3));
        assertFalse(replay.dispatchClaimed());
        assertEquals(claim.transaction().paymentTransactionId(), replay.transaction().paymentTransactionId());
        var succeeded = aggregateRepository.recordDisbursementOutcome(tenantId,
                claim.transaction().paymentTransactionId(), claim.transaction().dispatchAttemptId(),
                PaymentStatus.SUCCEEDED, "alphapay-private-v1-transaction", null,
                now.plusSeconds(4), java.time.LocalDate.of(2026, 3, 1), now.plusSeconds(4));

        var replayedSuccess = aggregateRepository.recordDisbursementOutcome(tenantId,
                claim.transaction().paymentTransactionId(), claim.transaction().dispatchAttemptId(),
                PaymentStatus.SUCCEEDED, "alphapay-private-v1-transaction", null,
                now.plusSeconds(4), java.time.LocalDate.of(2026, 3, 1), now.plusSeconds(5));

        assertEquals(PaymentTransactionStatus.SUCCEEDED, succeeded.transaction().status());
        assertEquals(LoanStatus.ACTIVE, replayedSuccess.loan().status());
        assertEquals(LoanStatus.ACTIVE, aggregateRepository.findLoan(tenantId, loan.loanId())
                .orElseThrow().status());
        RepaymentPlan activePlan = aggregateRepository.findRepaymentPlan(tenantId, loan.loanId())
                .orElseThrow();
        assertEquals(RepaymentPlanStatus.ACTIVE, activePlan.status());
        assertEquals(java.time.LocalDate.of(2026, 4, 1), activePlan.installments().getFirst().dueDate());
        assertEquals(ApplicationStatus.ORIGINATED, applicationRepository.find(tenantId,
                loan.applicationId()).orElseThrow().status());
        assertFalse(aggregateRepository.hasUnresolvedPaymentOwner(owner));
    }

    @Test
    void ambiguousDisbursementRequiresOwnerReconciliationAndNeverRedispatches() {
        Loan loan = prepareApprovedLoan("payment-unknown-app");
        Instant now = Instant.parse("2026-03-02T10:00:00Z");
        PluginKey originalOwner = new PluginKey("alphapay", "1.0.0");
        var claim = aggregateRepository.claimDisbursement(tenantId, loan.loanId(), originalOwner,
                UUID.randomUUID(), "ambiguous-key", "b".repeat(64), UUID.randomUUID(), now);
        var unknown = aggregateRepository.recordDisbursementOutcome(tenantId,
                claim.transaction().paymentTransactionId(), claim.transaction().dispatchAttemptId(),
                PaymentStatus.UNKNOWN, null, "PAYMENT_PROVIDER_TIMEOUT", null, null, now.plusSeconds(1));

        assertEquals(PaymentTransactionStatus.UNKNOWN, unknown.transaction().status());
        assertEquals(LoanStatus.DISBURSEMENT_PROCESSING, unknown.loan().status());
        assertEquals(OriginationWorkflowState.PAYMENT_RECONCILIATION_REQUIRED, unknown.workflow().state());
        assertTrue(aggregateRepository.hasUnresolvedPaymentOwner(originalOwner));
        assertThrows(LendingCoreException.class, () -> aggregateRepository.claimDisbursement(
                tenantId, loan.loanId(), new PluginKey("alphapay", "2.0.0"), UUID.randomUUID(),
                "ambiguous-key", "b".repeat(64), UUID.randomUUID(), now.plusSeconds(2)));
        var unresolvedReplay = aggregateRepository.claimDisbursement(tenantId, loan.loanId(),
                originalOwner, UUID.randomUUID(), "ambiguous-key", "b".repeat(64),
                UUID.randomUUID(), now.plusSeconds(2));
        assertFalse(unresolvedReplay.dispatchClaimed());
        assertEquals(PaymentTransactionStatus.UNKNOWN, unresolvedReplay.transaction().status());

        var resolved = aggregateRepository.recordDisbursementOutcome(tenantId,
                claim.transaction().paymentTransactionId(), claim.transaction().dispatchAttemptId(),
                PaymentStatus.SUCCEEDED, "alphapay-private-v1-reconciled", null,
                now.plusSeconds(3), java.time.LocalDate.of(2026, 3, 2), now.plusSeconds(3));

        assertEquals(LoanStatus.ACTIVE, aggregateRepository.findLoan(tenantId, loan.loanId())
                .orElseThrow().status());
        assertEquals(OriginationWorkflowState.ORIGINATED, resolved.workflow().state());
        assertFalse(aggregateRepository.hasUnresolvedPaymentOwner(originalOwner));
    }

    @Test
    void interruptedDisbursementIsPersistedUnknownAndTenantScopedLookupsFindIt() {
        Loan loan = prepareApprovedLoan("payment-interrupted-app");
        Instant now = Instant.parse("2026-03-02T11:00:00Z");
        PluginKey owner = new PluginKey("alphapay", "1.0.0");
        String idempotencyKey = "interrupted-dispatch-key";
        var claim = aggregateRepository.claimDisbursement(tenantId, loan.loanId(), owner,
                UUID.randomUUID(), idempotencyKey, "f".repeat(64), UUID.randomUUID(), now);

        var interrupted = aggregateRepository.markInterruptedDispatchUnknown(tenantId,
                claim.transaction().paymentTransactionId(), now.plusSeconds(1));

        assertEquals(PaymentTransactionStatus.UNKNOWN, interrupted.transaction().status());
        assertEquals(interrupted.transaction(), aggregateRepository.findPaymentTransaction(tenantId,
                claim.transaction().paymentTransactionId()).orElseThrow());
        assertEquals(interrupted.transaction(), aggregateRepository.findPaymentByLoan(tenantId,
                loan.loanId()).orElseThrow());
        assertEquals(interrupted.transaction(), aggregateRepository.findPaymentByIdempotencyKey(
                tenantId, idempotencyKey).orElseThrow());
        assertEquals(interrupted.transaction(), aggregateRepository.findDisbursement(tenantId,
                loan.loanId()).orElseThrow().transaction());
        assertTrue(aggregateRepository.findPaymentTransaction(UUID.randomUUID(),
                claim.transaction().paymentTransactionId()).isEmpty());
        assertTrue(aggregateRepository.findPaymentByIdempotencyKey(UUID.randomUUID(),
                idempotencyKey).isEmpty());
    }

    @Test
    void missingDisbursementLookupIsEmptyAndStaleOutcomeCannotChangePayment() {
        Loan loan = prepareApprovedLoan("payment-stale-attempt-app");
        Instant now = Instant.parse("2026-03-02T11:30:00Z");
        PluginKey owner = new PluginKey("alphapay", "1.0.0");

        assertTrue(aggregateRepository.findDisbursement(tenantId, loan.loanId()).isEmpty());
        var claim = aggregateRepository.claimDisbursement(tenantId, loan.loanId(), owner,
                UUID.randomUUID(), "stale-attempt-key", "a".repeat(64), UUID.randomUUID(), now);
        assertThrows(LendingCoreException.class, () -> aggregateRepository.recordDisbursementOutcome(
                tenantId, claim.transaction().paymentTransactionId(), UUID.randomUUID(),
                PaymentStatus.SUCCEEDED, "stale-provider-ref", null, now.plusSeconds(1),
                java.time.LocalDate.of(2026, 3, 2), now.plusSeconds(1)));
        assertEquals(PaymentTransactionStatus.SUBMISSION_IN_PROGRESS,
                aggregateRepository.findPaymentTransaction(tenantId,
                        claim.transaction().paymentTransactionId()).orElseThrow().status());
    }

    @Test
    void existingPaymentReplayRejectsChangedFingerprintAndOwner() {
        Loan loan = prepareApprovedLoan("payment-replay-conflict-app");
        Instant now = Instant.parse("2026-03-02T11:45:00Z");
        PluginKey owner = new PluginKey("alphapay", "1.0.0");
        String key = "payment-replay-conflict-key";
        aggregateRepository.claimDisbursement(tenantId, loan.loanId(), owner, UUID.randomUUID(),
                key, "b".repeat(64), UUID.randomUUID(), now);

        assertThrows(LendingCoreException.class, () -> aggregateRepository.claimDisbursement(
                tenantId, loan.loanId(), owner, UUID.randomUUID(), key, "c".repeat(64),
                UUID.randomUUID(), now.plusSeconds(1)));
        assertThrows(LendingCoreException.class, () -> aggregateRepository.claimDisbursement(
                tenantId, loan.loanId(), new PluginKey("alphapay", "2.0.0"), UUID.randomUUID(),
                key, "b".repeat(64), UUID.randomUUID(), now.plusSeconds(1)));
        assertTrue(aggregateRepository.hasUnresolvedPaymentOwner(owner));
    }

    @Test
    void processingPaymentOutcomeRemainsUnresolvedForReconciliation() {
        Loan loan = prepareApprovedLoan("payment-processing-app");
        Instant now = Instant.parse("2026-03-02T12:00:00Z");
        PluginKey owner = new PluginKey("alphapay", "1.0.0");
        var claim = aggregateRepository.claimDisbursement(tenantId, loan.loanId(), owner,
                UUID.randomUUID(), "processing-payment-key", "a".repeat(64), UUID.randomUUID(), now);

        var processing = aggregateRepository.recordDisbursementOutcome(tenantId,
                claim.transaction().paymentTransactionId(), claim.transaction().dispatchAttemptId(),
                PaymentStatus.PROCESSING, null, null, null, null, now.plusSeconds(1));
        var providerProcessing = aggregateRepository.recordDisbursementOutcome(tenantId,
                claim.transaction().paymentTransactionId(), claim.transaction().dispatchAttemptId(),
                PaymentStatus.PROCESSING, null, null, null, null, now.plusSeconds(2));

        assertEquals(PaymentTransactionStatus.UNKNOWN, processing.transaction().status());
        assertEquals(PaymentTransactionStatus.PROCESSING, providerProcessing.transaction().status());
        assertEquals(OriginationWorkflowState.PAYMENT_RECONCILIATION_REQUIRED, providerProcessing.workflow().state());
        assertTrue(aggregateRepository.hasUnresolvedPaymentOwner(owner));
    }

    @Test
    void compatibilityDisbursementClaimCreatesTransactionIdentity() {
        Loan loan = prepareApprovedLoan("payment-compatibility-claim-app");
        Instant now = Instant.parse("2026-03-02T13:00:00Z");

        var claim = aggregateRepository.claimDisbursement(tenantId, loan.loanId(),
                new PluginKey("alphapay", "1.0.0"), "compatibility-payment-key",
                "e".repeat(64), UUID.randomUUID(), now);

        assertTrue(claim.dispatchClaimed());
        assertTrue(aggregateRepository.findPaymentTransaction(tenantId,
                claim.transaction().paymentTransactionId()).isPresent());
    }

    @Test
    void definitivePaymentFailureIsTerminalAndDoesNotActivatePlan() {
        Loan loan = prepareApprovedLoan("payment-failed-app");
        Instant now = Instant.parse("2026-03-03T10:00:00Z");
        PluginKey owner = new PluginKey("alphapay", "1.0.0");
        var claim = aggregateRepository.claimDisbursement(tenantId, loan.loanId(), owner,
                UUID.randomUUID(), "definitive-failure-key", "c".repeat(64), UUID.randomUUID(), now);
        var failed = aggregateRepository.recordDisbursementOutcome(tenantId,
                claim.transaction().paymentTransactionId(), claim.transaction().dispatchAttemptId(),
                PaymentStatus.FAILED, null, "DECLINED", null, null, now.plusSeconds(1));

        assertEquals(PaymentTransactionStatus.FAILED, failed.transaction().status());
        assertEquals(LoanStatus.DISBURSEMENT_FAILED, failed.loan().status());
        assertEquals(RepaymentPlanStatus.PENDING_ACTIVATION, failed.plan().status());
        assertEquals(OriginationWorkflowState.PAYMENT_FAILED, failed.workflow().state());
        assertFalse(aggregateRepository.hasUnresolvedPaymentOwner(owner));
        assertThrows(LendingCoreException.class, () -> aggregateRepository.claimDisbursement(
                tenantId, loan.loanId(), owner, UUID.randomUUID(), "new-retry-key", "c".repeat(64),
                UUID.randomUUID(), now.plusSeconds(2)));
    }

    @Test
    void paymentClaimRejectsWrongPayloadOwnerAndPriorIntentReplay() {
        Loan loan = prepareApprovedLoan("payment-conflict-app");
        Instant now = Instant.parse("2026-03-05T10:00:00Z");
        PluginKey owner = new PluginKey("alphapay", "1.0.0");
        var claim = aggregateRepository.claimDisbursement(tenantId, loan.loanId(), owner,
                UUID.randomUUID(), "conflict-key", "f".repeat(64), UUID.randomUUID(), now);

        assertThrows(LendingCoreException.class, () -> aggregateRepository.claimDisbursement(
                tenantId, loan.loanId(), owner, UUID.randomUUID(), "conflict-key", "e".repeat(64),
                UUID.randomUUID(), now.plusSeconds(1)));
        assertThrows(LendingCoreException.class, () -> aggregateRepository.claimDisbursement(
                tenantId, loan.loanId(), new PluginKey("alphapay", "2.0.0"), UUID.randomUUID(),
                "conflict-key", "f".repeat(64), UUID.randomUUID(), now.plusSeconds(1)));

        var replay = aggregateRepository.claimDisbursement(tenantId, loan.loanId(), owner,
                UUID.randomUUID(), "conflict-key", "f".repeat(64), UUID.randomUUID(), now.plusSeconds(2));

        assertEquals(claim.transaction().paymentTransactionId(), replay.transaction().paymentTransactionId());
        assertFalse(replay.dispatchClaimed());
        assertEquals(PaymentTransactionStatus.UNKNOWN, replay.transaction().status());
    }

    @Test
    void paymentClaimRejectsKeyOwnedByDifferentLoan() {
        Loan firstLoan = prepareApprovedLoan("payment-key-owner-first");
        Instant now = Instant.parse("2026-03-08T10:00:00Z");
        PluginKey owner = new PluginKey("alphapay", "1.0.0");
        aggregateRepository.claimDisbursement(tenantId, firstLoan.loanId(), owner,
                UUID.randomUUID(), "shared-payment-key", "a".repeat(64), UUID.randomUUID(), now);
        Loan secondLoan = prepareApprovedLoan("payment-key-owner-second");

        assertThrows(LendingCoreException.class, () -> aggregateRepository.claimDisbursement(
                tenantId, secondLoan.loanId(), owner, UUID.randomUUID(), "shared-payment-key",
                "a".repeat(64), UUID.randomUUID(), now.plusSeconds(1)));
    }

    @Test
    void workflowAndLoanStatesMustBeReadyBeforePaymentClaim() {
        Loan unapproved = prepareApplicationWithoutRisk("payment-not-ready-app");
        aggregateRepository.originate(unapproved, plan(unapproved));
        assertThrows(LendingCoreException.class, () -> aggregateRepository.claimDisbursement(
                tenantId, unapproved.loanId(), new PluginKey("alphapay", "1.0.0"),
                UUID.randomUUID(), "not-ready-key", "d".repeat(64), UUID.randomUUID(), Instant.now()));
    }

    @Test
    void paymentClaimRejectsLoanOutsideDisbursementStates() {
        Loan loan = prepareApprovedLoan("payment-invalid-loan-state");
        jdbc.update("UPDATE loans SET status = 'DISBURSEMENT_FAILED' WHERE loan_id = ?", loan.loanId());

        assertThrows(LendingCoreException.class, () -> aggregateRepository.claimDisbursement(
                tenantId, loan.loanId(), new PluginKey("alphapay", "1.0.0"), UUID.randomUUID(),
                "invalid-loan-state-key", "a".repeat(64), UUID.randomUUID(), Instant.now()));
    }

    @Test
    void persistedSuccessWithoutConfirmationCanBeRecoveredAndActivated() {
        Loan loan = prepareApprovedLoan("payment-missing-confirmation");
        Instant now = Instant.parse("2026-03-09T10:00:00Z");
        var claim = aggregateRepository.claimDisbursement(tenantId, loan.loanId(),
                new PluginKey("alphapay", "1.0.0"), UUID.randomUUID(), "missing-confirmation-key",
                "c".repeat(64), UUID.randomUUID(), now);

        assertThrows(LendingCoreException.class, () -> aggregateRepository.recordDisbursementOutcome(
                tenantId, claim.transaction().paymentTransactionId(), claim.transaction().dispatchAttemptId(),
                PaymentStatus.SUCCEEDED, "recovered-provider-ref", null, null,
                java.time.LocalDate.of(2026, 3, 9), now.plusSeconds(1)));
        assertThrows(LendingCoreException.class, () -> aggregateRepository.recordDisbursementOutcome(
                tenantId, claim.transaction().paymentTransactionId(), claim.transaction().dispatchAttemptId(),
                PaymentStatus.SUCCEEDED, "recovered-provider-ref", null, now.plusSeconds(1),
                null, now.plusSeconds(2)));

        var recovered = aggregateRepository.recordDisbursementOutcome(tenantId,
                claim.transaction().paymentTransactionId(), claim.transaction().dispatchAttemptId(),
                PaymentStatus.SUCCEEDED, "recovered-provider-ref", null, now.plusSeconds(1),
                java.time.LocalDate.of(2026, 3, 9), now.plusSeconds(3));
        assertEquals(LoanStatus.ACTIVE, recovered.loan().status());
        assertEquals(RepaymentPlanStatus.ACTIVE, recovered.plan().status());
    }

    @Test
    void paymentOutcomeRejectsSupersededAttemptAndEmptyDisbursementLookupIsTenantSafe() {
        assertTrue(aggregateRepository.findDisbursement(tenantId, UUID.randomUUID()).isEmpty());
        Loan loan = prepareApprovedLoan("payment-stale-attempt-app");
        Instant now = Instant.parse("2026-03-06T10:00:00Z");
        var claim = aggregateRepository.claimDisbursement(tenantId, loan.loanId(),
                new PluginKey("alphapay", "1.0.0"), UUID.randomUUID(), "stale-attempt-key",
                "a".repeat(64), UUID.randomUUID(), now);
        assertThrows(LendingCoreException.class, () -> aggregateRepository.recordDisbursementOutcome(
                tenantId, claim.transaction().paymentTransactionId(), UUID.randomUUID(),
                PaymentStatus.SUCCEEDED, "provider-ref", null, now.plusSeconds(1),
                java.time.LocalDate.of(2026, 3, 6), now.plusSeconds(1)));
    }

    @Test
    void paymentOwnerRetentionOnlyBlocksUnresolvedTransactions() {
        Loan loan = prepareApprovedLoan("payment-retention-app");
        Instant now = Instant.parse("2026-03-07T10:00:00Z");
        PluginKey owner = new PluginKey("alphapay", "1.0.0");
        var claim = aggregateRepository.claimDisbursement(tenantId, loan.loanId(), owner,
                UUID.randomUUID(), "retention-key", "f".repeat(64), UUID.randomUUID(), now);
        assertTrue(aggregateRepository.hasUnresolvedPaymentOwner(owner));

        aggregateRepository.recordDisbursementOutcome(tenantId, claim.transaction().paymentTransactionId(),
                claim.transaction().dispatchAttemptId(), PaymentStatus.FAILED, null, "DECLINED",
                null, null, now.plusSeconds(1));

        assertFalse(aggregateRepository.hasUnresolvedPaymentOwner(owner));
        assertFalse(aggregateRepository.hasUnresolvedPaymentOwner(
                new PluginKey("alphapay", "2.0.0")));
    }

    @Test
    void persistedSuccessCanReplayLocalActivationAfterWorkflowCommitInterruption() {
        Loan loan = prepareApprovedLoan("payment-replay-app");
        Instant now = Instant.parse("2026-03-04T10:00:00Z");
        PluginKey owner = new PluginKey("alphapay", "1.0.0");
        var claim = aggregateRepository.claimDisbursement(tenantId, loan.loanId(), owner,
                UUID.randomUUID(), "success-replay-key", "e".repeat(64), UUID.randomUUID(), now);
        jdbc.update("UPDATE payment_transactions SET status = 'SUCCEEDED', "
                        + "provider_transaction_id = ?, updated_at = ?, version = version + 1 "
                        + "WHERE payment_transaction_id = ?",
                "alphapay-private-v1-replay", java.sql.Timestamp.from(now.plusSeconds(1)),
                claim.transaction().paymentTransactionId());

        var replay = aggregateRepository.recordDisbursementOutcome(tenantId,
                claim.transaction().paymentTransactionId(), claim.transaction().dispatchAttemptId(),
                PaymentStatus.SUCCEEDED, "alphapay-private-v1-replay", null,
                now.plusSeconds(1), java.time.LocalDate.of(2026, 3, 4), now.plusSeconds(2));

        assertEquals(LoanStatus.ACTIVE, replay.loan().status());
        assertEquals(RepaymentPlanStatus.ACTIVE, replay.plan().status());
        assertEquals(OriginationWorkflowState.ORIGINATED, replay.workflow().state());
    }

    private Loan prepareApprovedLoan(String key) {
        UUID applicationId = UUID.randomUUID();
        LoanApplication submitted = application(tenantId, applicationId, 0, ApplicationStatus.SUBMITTED);
        applicationRepository.insert(submitted, key, "d".repeat(64));
        Instant now = Instant.parse("2026-03-01T10:00:00Z");
        OriginationWorkflow workflow = aggregateRepository.startRisk(tenantId, applicationId, now);
        assessmentRepository.claimAssessment(tenantId, applicationId, workflow.activeAssessmentId(),
                workflow.workflowId(), workflow.activeRiskAttemptId(), workflow.version(), List.of(), now);
        RiskAssessment assessment = new RiskAssessment(workflow.activeAssessmentId(), applicationId,
                tenantId, AssessmentStatus.COMPLETED, 720, false, "NONE", AssessmentDecision.APPROVE,
                List.of("CLEAR"), List.of(), "demo-risk-policy-1", now, now.plusSeconds(1));
        assessmentRepository.completeAndRecord(assessment, AssessmentDecision.APPROVE,
                workflow.activeRiskAttemptId(), 1, workflow.version(), now.plusSeconds(1));
        Loan loan = loan(application(tenantId, applicationId, 1, ApplicationStatus.APPROVED));
        return aggregateRepository.originateApproved(loan, plan(loan));
    }

    private Loan prepareApplicationWithoutRisk(String key) {
        UUID applicationId = UUID.randomUUID();
        LoanApplication submitted = application(tenantId, applicationId, 0, ApplicationStatus.SUBMITTED);
        applicationRepository.insert(submitted, key, "e".repeat(64));
        Loan loan = loan(submitted);
        return aggregateRepository.originate(loan, plan(loan));
    }

    @Test
    void riskAssessmentRejectsMalformedPersistedProviderReferences() {
        applicationRepository.insert(application, "risk-malformed", "h".repeat(64));
        RiskAssessment assessment = assessment(application,
                List.of(new RiskAssessment.ProviderExecutionReference(UUID.randomUUID(),
                        "creditscorex", "1.0.0", "CREDIT_SCORE")));
        assessmentRepository.save(assessment);
        jdbc.update("UPDATE risk_assessments SET provider_execution_references = '{}'::jsonb "
                + "WHERE assessment_id = ?", assessment.assessmentId());
        assertThrows(LendingCoreException.class,
                () -> assessmentRepository.findLatest(tenantId, application.applicationId()));
        assertTrue(assessmentRepository.findLatest(UUID.randomUUID(), application.applicationId()).isEmpty());
    }

    @Test
    void riskAssessmentWriteFailuresAreWrapped() {
        RiskAssessment assessment = assessment(application, List.of());
        assertThrows(LendingCoreException.class, () -> assessmentRepository.save(assessment));
    }

    @Test
    void riskAssessmentSerializationFailuresAreWrapped() throws Exception {
        SimpleModule module = new SimpleModule();
        module.addSerializer(RiskAssessment.ProviderExecutionReference.class,
                new JsonSerializer<>() {
                    @Override
                    public void serialize(RiskAssessment.ProviderExecutionReference value,
                                          JsonGenerator generator,
                                          com.fasterxml.jackson.databind.SerializerProvider serializers)
                            throws java.io.IOException {
                        throw JsonMappingException.from(generator, "synthetic serializer failure");
                    }
                });
        ObjectMapper failingMapper = new ObjectMapper().registerModule(module);
        JdbcRiskAssessmentRepository failingRepository =
                new JdbcRiskAssessmentRepository(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()),
                        failingMapper);
        RiskAssessment assessment = assessment(application,
                List.of(new RiskAssessment.ProviderExecutionReference(UUID.randomUUID(),
                        "creditscorex", "1.0.0", "CREDIT_SCORE")));
        assertThrows(LendingCoreException.class, () -> failingRepository.save(assessment));
    }

    private LoanApplication application(UUID tenant, UUID id, long version, ApplicationStatus status) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new LoanApplication(id, tenant, "synthetic-applicant", "CASH_LOAN_DEMO", 1,
                new BigDecimal("1000.00"), Currency.getInstance("CNY"), 3, status, now, now, version);
    }

    private Loan loan(LoanApplication source) {
        return new Loan(UUID.randomUUID(), source.applicationId(), source.tenantId(),
                source.requestedAmount(), source.currency(), new BigDecimal("12.000000"), 3,
                source.productCode(), source.productVersion(), LoanStatus.PENDING_DISBURSEMENT,
                null, null, null, Instant.parse("2026-01-01T00:00:00Z"), 0);
    }

    private RepaymentPlan plan(Loan loan) {
        UUID planId = UUID.randomUUID();
        BigDecimal principalPart = new BigDecimal("333.33");
        List<RepaymentInstallment> installments = List.of(
                installment(planId, 1, "1000.00", "333.33", "10.00", "343.33", "666.67"),
                installment(planId, 2, "666.67", "333.33", "6.67", "340.00", "333.34"),
                installment(planId, 3, "333.34", "333.34", "3.33", "336.67", "0.00"));
        BigDecimal interest = new BigDecimal("20.00");
        assertEquals(0, principalPart.add(principalPart).add(new BigDecimal("333.34"))
                .compareTo(loan.principal()));
        return new RepaymentPlan(planId, loan.loanId(), loan.tenantId(), loan.principal(), interest,
                loan.principal().add(interest), 3, RepaymentPlanStatus.PENDING_ACTIVATION,
                loan.createdAt(), null, installments);
    }

    private static RepaymentInstallment installment(UUID planId, int number, String opening,
            String principal, String interest, String total, String remaining) {
        return new RepaymentInstallment(UUID.randomUUID(), planId, number, null,
                new BigDecimal(opening), new BigDecimal(principal), new BigDecimal(interest),
                new BigDecimal(total), new BigDecimal(remaining), InstallmentStatus.SCHEDULED);
    }

    private static RiskAssessment assessment(LoanApplication application,
            List<RiskAssessment.ProviderExecutionReference> references) {
        return new RiskAssessment(UUID.randomUUID(), application.applicationId(),
                application.tenantId(), AssessmentStatus.COMPLETED, 720, false, "NONE",
                AssessmentDecision.APPROVE, List.of("SCORE_ACCEPTABLE"), references,
                "demo-policy-1", Instant.now(), Instant.now());
    }
}
