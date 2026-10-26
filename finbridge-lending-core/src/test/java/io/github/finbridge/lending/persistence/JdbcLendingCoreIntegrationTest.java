package io.github.finbridge.lending.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.finbridge.lending.application.LendingCoreException;
import io.github.finbridge.lending.domain.ApplicationStatus;
import io.github.finbridge.lending.domain.AssessmentDecision;
import io.github.finbridge.lending.domain.AssessmentStatus;
import io.github.finbridge.lending.domain.InstallmentStatus;
import io.github.finbridge.lending.domain.Loan;
import io.github.finbridge.lending.domain.LoanApplication;
import io.github.finbridge.lending.domain.LoanStatus;
import io.github.finbridge.lending.domain.RepaymentInstallment;
import io.github.finbridge.lending.domain.RepaymentPlan;
import io.github.finbridge.lending.domain.RepaymentPlanStatus;
import io.github.finbridge.lending.domain.RiskAssessment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
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
        jdbc.update("DELETE FROM repayment_installments");
        jdbc.update("DELETE FROM repayment_plans");
        jdbc.update("DELETE FROM loans");
        jdbc.update("DELETE FROM risk_assessments");
        jdbc.update("DELETE FROM loan_applications");
        jdbc.update("DELETE FROM tenants WHERE tenant_id <> ?", tenantId);
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
