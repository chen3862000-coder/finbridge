package io.github.finbridge.lending.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.finbridge.lending.domain.ApplicationStatus;
import io.github.finbridge.lending.domain.Loan;
import io.github.finbridge.lending.domain.LoanApplication;
import io.github.finbridge.lending.domain.LoanProduct;
import io.github.finbridge.lending.domain.LoanStatus;
import io.github.finbridge.lending.persistence.LendingAggregateRepository;
import io.github.finbridge.lending.persistence.LoanApplicationRepository;
import io.github.finbridge.lending.persistence.LoanProductRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoanOriginationServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final ApplicationRepositoryStub applications = new ApplicationRepositoryStub();
    private final LoanProductRepository products = mock(LoanProductRepository.class);
    private final LendingAggregateRepository aggregates = mock(LendingAggregateRepository.class);
    private final LoanOriginationService service = new LoanOriginationService(applications, products,
            aggregates, new io.github.finbridge.lending.domain.RepaymentScheduleCalculator(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void createsPendingLoanAndPlanForApprovedApplicationOnly() {
        UUID tenant = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        LoanApplication approved = application(tenant, appId, ApplicationStatus.APPROVED);
        applications.applications.put(appId, approved);
        when(aggregates.findLoanByApplication(tenant, appId)).thenReturn(Optional.empty());
        when(products.findVersion("CASH_LOAN_DEMO", 1)).thenReturn(Optional.of(product()));
        when(aggregates.originateApproved(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Loan loan = service.originate(tenant, appId);
        assertEquals(LoanStatus.PENDING_DISBURSEMENT, loan.status());
        org.mockito.ArgumentCaptor<io.github.finbridge.lending.domain.RepaymentPlan> plan =
                org.mockito.ArgumentCaptor.forClass(io.github.finbridge.lending.domain.RepaymentPlan.class);
        org.mockito.ArgumentCaptor<Loan> savedLoan = org.mockito.ArgumentCaptor.forClass(Loan.class);
        org.mockito.Mockito.verify(aggregates).originateApproved(savedLoan.capture(), plan.capture());
        assertEquals(loan.loanId(), plan.getValue().loanId());
        assertEquals(LoanStatus.PENDING_DISBURSEMENT, savedLoan.getValue().status());
        assertEquals(3, plan.getValue().installments().size());
    }

    @Test
    void returnsExistingLoanAndRejectsNonApprovedApplication() {
        UUID tenant = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        LoanApplication approved = application(tenant, appId, ApplicationStatus.APPROVED);
        Loan existing = new Loan(UUID.randomUUID(), appId, tenant, new BigDecimal("1000.00"),
                Currency.getInstance("CNY"), BigDecimal.ZERO, 3, "CASH_LOAN_DEMO", 1,
                LoanStatus.PENDING_DISBURSEMENT, null, null, null, NOW, 0);
        applications.applications.put(appId, approved);
        when(aggregates.findLoanByApplication(tenant, appId)).thenReturn(Optional.of(existing));
        assertEquals(existing, service.originate(tenant, appId));

        UUID rejectedId = UUID.randomUUID();
        applications.applications.put(rejectedId,
                application(tenant, rejectedId, ApplicationStatus.REJECTED));
        assertThrows(LendingCoreException.class, () -> service.originate(tenant, rejectedId));
    }

    @Test
    void returnsExistingLoanWithoutResolvingAProductAgain() {
        UUID tenant = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        applications.applications.put(appId,
                application(tenant, appId, ApplicationStatus.APPROVED));
        Loan existing = new Loan(UUID.randomUUID(), appId, tenant, new BigDecimal("1000.00"),
                Currency.getInstance("CNY"), BigDecimal.ZERO, 3, "CASH_LOAN_DEMO", 1,
                LoanStatus.PENDING_DISBURSEMENT, null, null, null, NOW, 0);
        when(aggregates.findLoanByApplication(tenant, appId)).thenReturn(Optional.of(existing));
        assertEquals(existing, service.originate(tenant, appId));
        org.mockito.Mockito.verifyNoInteractions(products);
    }

    @Test
    void requiresTheProductVersionSelectedAtApplicationTime() {
        UUID tenant = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        applications.applications.put(appId,
                application(tenant, appId, ApplicationStatus.APPROVED));
        when(aggregates.findLoanByApplication(tenant, appId)).thenReturn(Optional.empty());
        when(products.findVersion("CASH_LOAN_DEMO", 1)).thenReturn(Optional.empty());
        when(products.findVersion("CASH_LOAN_DEMO", 2)).thenReturn(Optional.of(new LoanProduct(
                "CASH_LOAN_DEMO", 2, Currency.getInstance("CNY"), new BigDecimal("1000.00"),
                new BigDecimal("50000.00"), Set.of(3, 6, 12), new BigDecimal("12.000000"))));
        assertThrows(LendingCoreException.class, () -> service.originate(tenant, appId));
    }

    @Test
    void readsPreviousProductVersionWhenCurrentProductHasAdvanced() {
        UUID tenant = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        LoanApplication approved = application(tenant, appId, ApplicationStatus.APPROVED);
        applications.applications.put(appId, approved);
        when(aggregates.findLoanByApplication(tenant, appId)).thenReturn(Optional.empty());
        when(products.findVersion("CASH_LOAN_DEMO", 1)).thenReturn(Optional.of(product()));
        when(aggregates.originateApproved(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        assertEquals(LoanStatus.PENDING_DISBURSEMENT, service.originate(tenant, appId).status());
    }

    @Test
    void originatesWhenExactProductVersionIsAvailable() {
        UUID tenant = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        applications.applications.put(appId,
                application(tenant, appId, ApplicationStatus.APPROVED));
        when(aggregates.findLoanByApplication(tenant, appId)).thenReturn(Optional.empty());
        when(products.findVersion("CASH_LOAN_DEMO", 1)).thenReturn(Optional.of(product()));
        when(aggregates.originateApproved(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Loan originated = service.originate(tenant, appId);
        assertEquals(LoanStatus.PENDING_DISBURSEMENT, originated.status());
        org.mockito.Mockito.verify(aggregates).originateApproved(
                org.mockito.ArgumentMatchers.eq(originated), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingApplicationCannotOriginate() {
        assertThrows(LendingCoreException.class,
                () -> service.originate(UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void listsRecentLoansForTheRequestedTenantAndLimit() {
        UUID tenant = UUID.randomUUID();
        List<Loan> expected = List.of(mock(Loan.class));
        when(aggregates.findRecentLoans(tenant, 7)).thenReturn(expected);

        assertEquals(expected, service.listLoans(tenant, 7));
        org.mockito.Mockito.verify(aggregates).findRecentLoans(tenant, 7);
    }

    @Test
    void failsWhenCurrentProductCannotSupplyTheApplicationVersion() {
        UUID tenant = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        applications.applications.put(appId,
                application(tenant, appId, ApplicationStatus.APPROVED));
        when(aggregates.findLoanByApplication(tenant, appId)).thenReturn(Optional.empty());
        when(products.findVersion("CASH_LOAN_DEMO", 1)).thenReturn(Optional.empty());
        assertThrows(LendingCoreException.class, () -> service.originate(tenant, appId));
    }

    @Test
    void lookupIsTenantScoped() {
        UUID tenant = UUID.randomUUID();
        UUID loanId = UUID.randomUUID();
        when(aggregates.findLoan(tenant, loanId)).thenReturn(Optional.empty());
        assertThrows(LendingCoreException.class, () -> service.getLoan(tenant, loanId));
        Loan expected = new Loan(loanId, UUID.randomUUID(), tenant, new BigDecimal("1000.00"),
                Currency.getInstance("CNY"), BigDecimal.ZERO, 3, "CASH_LOAN_DEMO", 1,
                LoanStatus.PENDING_DISBURSEMENT, null, null, null, NOW, 0);
        when(aggregates.findLoan(tenant, loanId)).thenReturn(Optional.of(expected));
        assertEquals(expected, service.getLoan(tenant, loanId));
    }

    @Test
    void disbursementLookupReturnsDurableClaimAndFailsWhenAbsent() {
        UUID tenant = UUID.randomUUID();
        UUID loanId = UUID.randomUUID();
        LendingAggregateRepository.DisbursementClaim expected =
                mock(LendingAggregateRepository.DisbursementClaim.class);
        when(aggregates.findDisbursement(tenant, loanId)).thenReturn(Optional.of(expected));
        assertEquals(expected, service.getDisbursement(tenant, loanId));

        when(aggregates.findDisbursement(tenant, loanId)).thenReturn(Optional.empty());
        assertThrows(LendingCoreException.class, () -> service.getDisbursement(tenant, loanId));
    }

    private static LoanApplication application(UUID tenant, UUID id, ApplicationStatus status) {
        return new LoanApplication(id, tenant, "synthetic-applicant", "CASH_LOAN_DEMO", 1,
                new BigDecimal("1000.00"), Currency.getInstance("CNY"), 3, status, NOW, NOW, 0);
    }

    private static LoanProduct product() {
        return new LoanProduct("CASH_LOAN_DEMO", 1, Currency.getInstance("CNY"),
                new BigDecimal("1000.00"), new BigDecimal("50000.00"), Set.of(3, 6, 12),
                new BigDecimal("12.000000"));
    }

    private static final class ApplicationRepositoryStub implements LoanApplicationRepository {
        private final java.util.Map<UUID, LoanApplication> applications = new java.util.HashMap<>();

        @Override
        public LoanApplication insert(LoanApplication application, String idempotencyKey,
                                      String requestFingerprint) {
            applications.put(application.applicationId(), application);
            return application;
        }

        @Override
        public Optional<LoanApplication> find(UUID tenant, UUID applicationId) {
            return Optional.ofNullable(applications.get(applicationId))
                    .filter(application -> application.tenantId().equals(tenant));
        }

        @Override
        public List<LoanApplication> findRecent(UUID tenant, int limit) {
            return applications.values().stream()
                    .filter(application -> application.tenantId().equals(tenant))
                    .limit(limit).toList();
        }

        @Override
        public LoanApplication updateStatus(LoanApplication application, long expectedVersion) {
            applications.put(application.applicationId(), application);
            return application;
        }
    }
}
