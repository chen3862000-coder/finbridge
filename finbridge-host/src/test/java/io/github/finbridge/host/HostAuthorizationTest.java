package io.github.finbridge.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.runtime.PluginHandle;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class HostAuthorizationTest {
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantFacadeIdentityComesOnlyFromFinBridgePrincipal() {
        UUID tenant = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new FinBridgePrincipal("tenant-user", tenant),
                        "", List.of()));

        assertEquals(tenant, HostAuthorization.tenantPrincipal().tenantId());
        HostAuthorization.requireOwnTenant(tenant);
        assertThrows(AccessDeniedException.class,
                () -> HostAuthorization.requireOwnTenant(UUID.randomUUID()));
        FinBridgePrincipal forgedPrincipal = new FinBridgePrincipal("attacker", tenant);
        assertThrows(AccessDeniedException.class,
                () -> HostAuthorization.asTenant(forgedPrincipal, () -> "should-not-run"));
    }

    @Test
    void rejectsMissingForgedAndNonAdminIdentities() {
        assertThrows(AccessDeniedException.class, HostAuthorization::tenantPrincipal);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client-supplied-tenant", "", List.of()));
        assertThrows(AccessDeniedException.class, HostAuthorization::tenantPrincipal);
        assertThrows(AccessDeniedException.class, HostAuthorization::requireAdmin);
    }

    @Test
    void adminRoleIsRequiredForPluginControl() {
        FinBridgePrincipal admin = new FinBridgePrincipal("operator", null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                admin, "", List.of(new SimpleGrantedAuthority("ROLE_FINBRIDGE_ADMIN"))));

        assertEquals("operator", HostAuthorization.requireAdmin());
        assertThrows(AccessDeniedException.class, () -> HostAuthorization.requireOwnTenant(UUID.randomUUID()));
    }

    @Test
    void adminFacadeRequiresRoleAndReturnsAuthenticatedActor() {
        PluginLifecycleManager lifecycle = mock(PluginLifecycleManager.class);
        PluginAdminFacade facade = new PluginAdminFacade(lifecycle);
        UUID tenant = UUID.randomUUID();
        when(lifecycle.bindings(tenant)).thenReturn(List.of());
        assertThrows(AccessDeniedException.class, () -> facade.bindings(tenant));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "operator", "", List.of(new SimpleGrantedAuthority("ROLE_FINBRIDGE_ADMIN"))));
        assertEquals(List.of(), facade.bindings(tenant));
    }

    @Test
    void authenticatedCallerCannotSubstituteAnotherFinBridgePrincipal() {
        FinBridgePrincipal actual = new FinBridgePrincipal("actual", UUID.randomUUID());
        FinBridgePrincipal forged = new FinBridgePrincipal("forged", UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actual, "", List.of()));

        assertThrows(AccessDeniedException.class,
                () -> HostAuthorization.asTenant(forged, () -> "forged-operation"));
        assertEquals(actual, HostAuthorization.tenantPrincipal());
    }

    @Test
    void nestedTenantFacadeCallDoesNotReplaceSecurityContext() {
        FinBridgePrincipal actual = new FinBridgePrincipal("actual", UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actual, "", List.of()));

        assertEquals("result", HostAuthorization.asTenant(actual, () -> "result"));
        assertEquals(actual, HostAuthorization.tenantPrincipal());
    }

    @Test
    void tenantFacadeUsesPrincipalTenantForApplicationCreationAndTenantReads() {
        UUID tenant = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        var application = mock(io.github.finbridge.lending.domain.LoanApplication.class);
        var applications = mock(io.github.finbridge.lending.application.LendingApplicationService.class);
        var risk = mock(io.github.finbridge.lending.application.RiskAssessmentService.class);
        var payments = mock(io.github.finbridge.lending.application.DisbursementService.class);
        var origination = mock(io.github.finbridge.lending.application.LoanOriginationService.class);
        var consoleReads = mock(TenantConsoleReadRepository.class);
        TenantLendingFacade facade = new TenantLendingFacade(applications, risk, payments, origination,
                consoleReads);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new FinBridgePrincipal("tenant-user", tenant), "", List.of()));
        var command = org.mockito.ArgumentCaptor.forClass(
                io.github.finbridge.lending.application.CreateLoanApplicationCommand.class);
        var created = mock(io.github.finbridge.lending.domain.LoanApplication.class);
        when(applications.createApplication(command.capture())).thenReturn(created);
        when(applications.getApplication(tenant, applicationId)).thenReturn(application);
        var assessment = mock(io.github.finbridge.lending.domain.RiskAssessment.class);
        when(risk.evaluate(tenant, applicationId, "tenant-correlation")).thenReturn(assessment);
        when(risk.getLatest(tenant, applicationId)).thenReturn(assessment);
        var loan = mock(io.github.finbridge.lending.domain.Loan.class);
        when(origination.originate(tenant, applicationId)).thenReturn(loan);
        var claim = mock(io.github.finbridge.lending.persistence.LendingAggregateRepository.DisbursementClaim.class);
        UUID loanId = UUID.randomUUID();
        var missingPayment = new io.github.finbridge.lending.application.LendingCoreException("missing payment");
        when(payments.getStatus(tenant, loanId)).thenReturn(claim);
        when(payments.reconcile(tenant, loanId, "payment-correlation")).thenReturn(claim);
        when(payments.dispatch(tenant, loanId, "payment-key", "payment-correlation")).thenReturn(claim);
        when(payments.getStatus(tenant, UUID.nameUUIDFromBytes(
                "missing-payment".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .thenThrow(missingPayment);
        when(payments.reconcile(tenant, UUID.nameUUIDFromBytes(
                "missing-payment".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "payment-correlation"))
                .thenThrow(missingPayment);
        when(payments.dispatch(tenant, UUID.nameUUIDFromBytes(
                "missing-payment".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "payment-key", "payment-correlation")).thenThrow(missingPayment);

        assertEquals(created, facade.createApplication("synthetic-applicant", "CASH_LOAN_DEMO",
                new java.math.BigDecimal("1200.00"), java.util.Currency.getInstance("CNY"), 6, "tenant-key"));
        assertEquals(tenant, command.getValue().tenantId());
        assertEquals(application, facade.application(applicationId));
        var lookupOrder = inOrder(applications, risk, origination);
        lookupOrder.verify(applications).getApplication(tenant, applicationId);
        assertEquals(assessment, facade.evaluate(applicationId, "tenant-correlation"));
        lookupOrder.verify(applications).getApplication(tenant, applicationId);
        lookupOrder.verify(risk).evaluate(tenant, applicationId, "tenant-correlation");
        assertEquals(assessment, facade.riskAssessment(applicationId));
        assertEquals(loan, facade.originate(applicationId));
        assertEquals(claim, facade.payment(loanId));
        assertEquals(claim, facade.reconcile(loanId, "payment-correlation"));
        assertEquals(claim, facade.dispatch(loanId, "payment-key", "payment-correlation"));
        verify(payments).getStatus(tenant, loanId);
        verify(payments).reconcile(tenant, loanId, "payment-correlation");
        verify(payments).dispatch(tenant, loanId, "payment-key", "payment-correlation");
        UUID missingLoan = UUID.nameUUIDFromBytes(
                "missing-payment".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(missingPayment, assertThrows(io.github.finbridge.lending.application.LendingCoreException.class,
                () -> facade.payment(missingLoan)).getCause());
        assertEquals(missingPayment, assertThrows(io.github.finbridge.lending.application.LendingCoreException.class,
                () -> facade.reconcile(missingLoan, "payment-correlation")).getCause());
        assertEquals(missingPayment, assertThrows(io.github.finbridge.lending.application.LendingCoreException.class,
                () -> facade.dispatch(missingLoan, "payment-key", "payment-correlation")).getCause());
    }

    @Test
    void borrowerFacadeScopesSyntheticApplicantAndApplicationListToPrincipal() {
        UUID tenant = UUID.randomUUID();
        var applications = mock(io.github.finbridge.lending.application.LendingApplicationService.class);
        var facade = new TenantLendingFacade(applications,
                mock(io.github.finbridge.lending.application.RiskAssessmentService.class),
                mock(io.github.finbridge.lending.application.DisbursementService.class),
                mock(io.github.finbridge.lending.application.LoanOriginationService.class),
                mock(TenantConsoleReadRepository.class));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new FinBridgePrincipal("borrower-a", tenant), "", List.of()));
        var created = mock(io.github.finbridge.lending.domain.LoanApplication.class);
        when(applications.createApplication(org.mockito.ArgumentMatchers.any())).thenReturn(created);
        when(applications.listApplications(tenant, 10)).thenReturn(List.of());

        assertEquals(created, facade.applyForCurrentTenant("self", "CASH_LOAN_DEMO",
                new java.math.BigDecimal("1200.00"), java.util.Currency.getInstance("CNY"), 3, "borrower-key"));
        var command = org.mockito.ArgumentCaptor.forClass(
                io.github.finbridge.lending.application.CreateLoanApplicationCommand.class);
        verify(applications).createApplication(command.capture());
        assertEquals(tenant, command.getValue().tenantId());
        assertEquals("demo-borrower-a-self", command.getValue().applicantId());
        assertEquals(List.of(), facade.listApplicationsForCurrentTenant(10));
        verify(applications).listApplications(tenant, 10);

    }

    @Test
    void tenantFacadePreservesNotFoundCauseAndDoesNotCallApplicationServicesAnonymously() {
        UUID tenant = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        var applications = mock(io.github.finbridge.lending.application.LendingApplicationService.class);
        var risk = mock(io.github.finbridge.lending.application.RiskAssessmentService.class);
        var payments = mock(io.github.finbridge.lending.application.DisbursementService.class);
        var origination = mock(io.github.finbridge.lending.application.LoanOriginationService.class);
        var consoleReads = mock(TenantConsoleReadRepository.class);
        TenantLendingFacade facade = new TenantLendingFacade(applications, risk, payments, origination,
                consoleReads);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new FinBridgePrincipal("tenant-user", tenant), "", List.of()));
        var missing = new io.github.finbridge.lending.application.LendingCoreException("missing");
        when(applications.getApplication(tenant, applicationId)).thenThrow(missing);

        var mapped = assertThrows(io.github.finbridge.lending.application.LendingCoreException.class,
                () -> facade.application(applicationId));
        assertEquals(missing, mapped.getCause());
        when(risk.getLatest(tenant, applicationId)).thenThrow(missing);
        assertEquals(missing, assertThrows(io.github.finbridge.lending.application.LendingCoreException.class,
                () -> facade.riskAssessment(applicationId)).getCause());
        SecurityContextHolder.clearContext();
        assertThrows(AccessDeniedException.class, () -> facade.createApplication("synthetic-applicant",
                "CASH_LOAN_DEMO", java.math.BigDecimal.ONE, java.util.Currency.getInstance("CNY"), 3, "key"));
        verifyNoInteractions(payments, origination);
    }

    @Test
    void adminFacadeDelegatesLifecycleActionsOnlyWithAdminRole() throws Exception {
        PluginLifecycleManager lifecycle = mock(PluginLifecycleManager.class);
        PluginAdminFacade facade = new PluginAdminFacade(lifecycle);
        PluginKey key = new PluginKey("admin-pay", "1.0.0");
        UUID tenant = UUID.randomUUID();
        PluginHandle handle = mock(PluginHandle.class);
        when(lifecycle.install("artifact.jar")).thenReturn(handle);
        when(lifecycle.activate(key)).thenReturn(handle);
        when(lifecycle.upgrade(tenant, io.github.finbridge.spi.Capability.DISBURSE, 1,
                "replacement.jar", java.time.Duration.ZERO)).thenReturn(handle);
        when(lifecycle.rollback(tenant, key, io.github.finbridge.spi.Capability.DISBURSE, 2,
                java.time.Duration.ZERO)).thenReturn(handle);
        when(lifecycle.bindings(tenant)).thenReturn(List.of());
        when(lifecycle.artifacts()).thenReturn(List.of());
        when(lifecycle.tenants()).thenReturn(List.of());
        when(lifecycle.events(key)).thenReturn(List.of());
        when(lifecycle.drain(key, java.time.Duration.ZERO)).thenReturn(true);
        assertThrows(AccessDeniedException.class, () -> facade.activate(key));
        verifyNoInteractions(lifecycle);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "operator", "", List.of(new SimpleGrantedAuthority("ROLE_FINBRIDGE_ADMIN"))));

        facade.install("artifact.jar");
        facade.activate(key);
        facade.bind(tenant, io.github.finbridge.spi.Capability.DISBURSE, key, 0);
        facade.unbind(tenant, io.github.finbridge.spi.Capability.DISBURSE, 1);
        facade.upgrade(tenant, io.github.finbridge.spi.Capability.DISBURSE, 1,
                "replacement.jar", java.time.Duration.ZERO);
        facade.rollback(tenant, key, io.github.finbridge.spi.Capability.DISBURSE, 2,
                java.time.Duration.ZERO);
        facade.drain(key, java.time.Duration.ZERO);
        facade.unload(key, java.time.Duration.ZERO);
        facade.auditEvents(key);
        facade.bindings(tenant);
        assertEquals(List.of(), facade.artifacts());
        assertEquals(List.of(), facade.tenants());
        verify(lifecycle).install("artifact.jar");
        verify(lifecycle).activate(key);
        verify(lifecycle).bind(tenant, io.github.finbridge.spi.Capability.DISBURSE, key, 0);
        verify(lifecycle).unbind(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(io.github.finbridge.spi.Capability.DISBURSE),
                org.mockito.ArgumentMatchers.eq(1L));
        verify(lifecycle).upgrade(tenant, io.github.finbridge.spi.Capability.DISBURSE, 1,
                "replacement.jar", java.time.Duration.ZERO);
        verify(lifecycle).rollback(tenant, key, io.github.finbridge.spi.Capability.DISBURSE, 2,
                java.time.Duration.ZERO);
        verify(lifecycle).drain(key, java.time.Duration.ZERO);
        verify(lifecycle).unload(key, java.time.Duration.ZERO);
        verify(lifecycle).events(key);
        verify(lifecycle).bindings(tenant);
        verify(lifecycle).artifacts();
        verify(lifecycle).tenants();
    }
}
