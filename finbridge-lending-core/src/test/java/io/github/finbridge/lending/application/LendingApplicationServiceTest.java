package io.github.finbridge.lending.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.finbridge.lending.domain.ApplicationStatus;
import io.github.finbridge.lending.domain.AssessmentDecision;
import io.github.finbridge.lending.domain.LoanApplication;
import io.github.finbridge.lending.domain.LoanProduct;
import io.github.finbridge.lending.persistence.LoanApplicationRepository;
import io.github.finbridge.lending.persistence.LoanProductRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LendingApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final ApplicationRepositoryMock applications = new ApplicationRepositoryMock();
    private final LoanProductRepository products = mock(LoanProductRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final LendingApplicationService service =
            new LendingApplicationService(applications, products, clock);
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(products.findActive("CASH_LOAN_DEMO")).thenReturn(Optional.of(product()));
    }

    @Test
    void createsSyntheticApplicationAfterProductValidation() {
        applications.onInsert = application -> application;
        LoanApplication created = service.createApplication(command("create-key", "1000.00", 3));
        assertEquals(ApplicationStatus.SUBMITTED, created.status());
        assertEquals(NOW, created.createdAt());
        assertEquals("synthetic-applicant", created.applicantId());
        org.mockito.Mockito.verify(products).findActive("CASH_LOAN_DEMO");
        assertEquals(1, applications.insertCalls);
    }

    @Test
    void missingActiveProductIsRejected() {
        when(products.findActive("CASH_LOAN_DEMO")).thenReturn(Optional.empty());
        assertThrows(LendingCoreException.class,
                () -> service.createApplication(command("missing-product", "1000.00", 3)));
    }

    @Test
    void rejectsInvalidProductTermsBeforePersistence() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createApplication(command("key", "500.00", 3)));
        assertThrows(IllegalArgumentException.class,
                () -> service.createApplication(command("key", "1000.00", 9)));
    }

    @Test
    void rejectsBlankInputCommand() {
        assertThrows(NullPointerException.class, () -> new CreateLoanApplicationCommand(null,
                "applicant", "product", BigDecimal.ONE, Currency.getInstance("CNY"), 3, "key"));
        assertThrows(IllegalArgumentException.class, () -> new CreateLoanApplicationCommand(tenantId,
                " ", "product", BigDecimal.ONE, Currency.getInstance("CNY"), 3, "key"));
    }

    @Test
    void createsSameFingerprintForEquivalentCommandsAndChangesForDifferentPayload() {
        applications.onInsert = application -> application;
        service.createApplication(command("one", "1000.00", 3));
        service.createApplication(command("two", "1000.00", 3));
        service.createApplication(command("three", "2000.00", 3));
        assertEquals(applications.fingerprints.get(0), applications.fingerprints.get(1));
        org.junit.jupiter.api.Assertions.assertNotEquals(applications.fingerprints.get(1),
                applications.fingerprints.get(2));
    }

    @Test
    void performsTenantScopedTransitionsAndMapsRiskDecisionToApplicationState() {
        LoanApplication submitted = application(ApplicationStatus.SUBMITTED, 0);
        applications.applications.put(submitted.applicationId(), submitted);
        assertEquals(ApplicationStatus.RISK_EVALUATING,
                service.startRiskEvaluation(tenantId, submitted.applicationId()).status());

        LoanApplication evaluating = submitted.transitionTo(ApplicationStatus.RISK_EVALUATING, NOW);
        applications.applications.put(evaluating.applicationId(), evaluating);
        assertEquals(ApplicationStatus.APPROVED,
                service.recordRiskDecision(tenantId, evaluating.applicationId(), AssessmentDecision.APPROVE)
                        .status());
        applications.applications.put(evaluating.applicationId(), evaluating);
        assertEquals(ApplicationStatus.REJECTED,
                service.recordRiskDecision(tenantId, evaluating.applicationId(), AssessmentDecision.REJECT)
                        .status());
        applications.applications.put(evaluating.applicationId(), evaluating);
        assertEquals(ApplicationStatus.MANUAL_REVIEW,
                service.recordRiskDecision(tenantId, evaluating.applicationId(), AssessmentDecision.REVIEW)
                        .status());
    }

    @Test
    void completesOriginationAndSupportsCancellationFromEligibleStates() {
        LoanApplication submitted = application(ApplicationStatus.SUBMITTED, 0);
        applications.applications.put(submitted.applicationId(), submitted);
        LoanApplication cancelled = service.cancel(tenantId, submitted.applicationId());
        assertEquals(ApplicationStatus.CANCELLED, cancelled.status());
        assertThrows(IllegalStateException.class,
                () -> service.cancel(tenantId, submitted.applicationId()));

        LoanApplication approved = application(ApplicationStatus.APPROVED, 3);
        applications.applications.put(approved.applicationId(), approved);
        LoanApplication inProgress = service.markOriginationInProgress(tenantId,
                approved.applicationId());
        assertEquals(ApplicationStatus.ORIGINATION_IN_PROGRESS, inProgress.status());
        assertEquals(ApplicationStatus.ORIGINATED,
                service.markOriginated(tenantId, approved.applicationId()).status());
    }

    @Test
    void repositoryVersionConflictPropagatesAsDomainError() {
        LoanApplication submitted = application(ApplicationStatus.SUBMITTED, 0);
        applications.applications.put(submitted.applicationId(), submitted);
        applications.failUpdate = true;
        assertThrows(LendingCoreException.class,
                () -> service.startRiskEvaluation(tenantId, submitted.applicationId()));
    }

    @Test
    void missingTenantOwnedApplicationIsNotFound() {
        UUID unknown = UUID.randomUUID();
        assertThrows(LendingCoreException.class, () -> service.getApplication(tenantId, unknown));
    }

    private CreateLoanApplicationCommand command(String key, String amount, int term) {
        return new CreateLoanApplicationCommand(tenantId, "synthetic-applicant", "CASH_LOAN_DEMO",
                new BigDecimal(amount), Currency.getInstance("CNY"), term, key);
    }

    private LoanApplication application(ApplicationStatus status, long version) {
        return new LoanApplication(UUID.randomUUID(), tenantId, "synthetic-applicant", "CASH_LOAN_DEMO",
                1, new BigDecimal("1000.00"), Currency.getInstance("CNY"), 3, status, NOW, NOW, version);
    }

    private static LoanProduct product() {
        return new LoanProduct("CASH_LOAN_DEMO", 1, Currency.getInstance("CNY"),
                new BigDecimal("1000.00"), new BigDecimal("50000.00"), Set.of(3, 6, 12),
                new BigDecimal("12.000000"));
    }

    private static final class ApplicationRepositoryMock implements LoanApplicationRepository {
        private final java.util.Map<UUID, LoanApplication> applications = new java.util.HashMap<>();
        private final java.util.List<String> fingerprints = new java.util.ArrayList<>();
        private java.util.function.Function<LoanApplication, LoanApplication> onInsert =
                application -> application;
        private int insertCalls;
        private boolean failUpdate;

        @Override
        public LoanApplication insert(LoanApplication application, String idempotencyKey,
                                      String requestFingerprint) {
            insertCalls++;
            fingerprints.add(requestFingerprint);
            return onInsert.apply(application);
        }

        @Override
        public Optional<LoanApplication> find(UUID tenant, UUID applicationId) {
            return Optional.ofNullable(applications.get(applicationId))
                    .filter(application -> application.tenantId().equals(tenant));
        }

        @Override
        public LoanApplication updateStatus(LoanApplication application, long expectedVersion) {
            if (failUpdate) {
                throw new LendingCoreException("Application version conflict");
            }
            LoanApplication current = applications.get(application.applicationId());
            if (current == null || current.version() != expectedVersion) {
                throw new LendingCoreException("Application version conflict");
            }
            applications.put(application.applicationId(), application);
            return application;
        }
    }
}
