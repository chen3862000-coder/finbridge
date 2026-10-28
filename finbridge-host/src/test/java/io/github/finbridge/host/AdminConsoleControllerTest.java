package io.github.finbridge.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;

import io.github.finbridge.runtime.PluginBinding;
import io.github.finbridge.runtime.PluginHandle;
import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.spi.PluginType;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AdminConsoleControllerTest {
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deniesRegistryAndLifecycleEndpointsWithoutAdminBeforeDelegation() throws Exception {
        PluginAdminFacade admin = mock(PluginAdminFacade.class);
        AdminConsoleController controller = new AdminConsoleController(admin);
        UUID tenant = UUID.randomUUID();
        PluginKey key = new PluginKey("console-pay", "1.0.0");

        assertThrows(AccessDeniedException.class, controller::artifacts);
        assertThrows(AccessDeniedException.class, controller::tenants);
        assertThrows(AccessDeniedException.class, () -> controller.bindings(tenant));
        assertThrows(AccessDeniedException.class, () -> controller.install(
                new AdminConsoleController.ArtifactRequest("approved.jar")));
        assertThrows(AccessDeniedException.class, () -> controller.activate(key.pluginId(), key.pluginVersion()));
        assertThrows(AccessDeniedException.class,
                () -> controller.drain(key.pluginId(), key.pluginVersion(), Duration.ZERO));
        verifyNoInteractions(admin);
    }

    @Test
    void adminLifecycleResponsesExposePersistedArtifactSummaryNotRuntimeHandle() {
        PluginAdminFacade admin = mock(PluginAdminFacade.class);
        AdminConsoleController controller = new AdminConsoleController(admin);
        PluginKey key = new PluginKey("console-pay", "1.0.0");
        PluginHandle handle = mock(PluginHandle.class);
        var artifact = new PluginControlPlaneRepository.RegisteredArtifact("console-pay.jar", key,
                "a".repeat(64), PluginType.PAYMENT, Set.of(Capability.DISBURSE), "LOADED", false);
        var binding = new PluginBinding(UUID.randomUUID(), Capability.DISBURSE, key, 1);
        when(handle.key()).thenReturn(key);
        when(admin.install("console-pay.jar")).thenReturn(handle);
        when(admin.artifacts()).thenReturn(List.of(artifact));
        when(admin.bind(any(), any(), any(), anyLong())).thenReturn(binding);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "operator", "", List.of(new SimpleGrantedAuthority("ROLE_FINBRIDGE_ADMIN"))));

        var response = controller.install(new AdminConsoleController.ArtifactRequest("console-pay.jar"));

        assertEquals(artifact, response);
        assertEquals(binding, controller.bind(new AdminConsoleController.BindingRequest(binding.tenantId(),
                Capability.DISBURSE, key.pluginId(), key.pluginVersion(), 0)));
        verify(admin).install("console-pay.jar");
    }

    @Test
    void adminBindingAndLifecycleRoutesDelegateAfterAuthorization() throws Exception {
        PluginAdminFacade admin = mock(PluginAdminFacade.class);
        AdminConsoleController controller = new AdminConsoleController(admin);
        PluginKey key = new PluginKey("console-pay", "2.0.0");
        UUID tenant = UUID.randomUUID();
        Duration timeout = Duration.ofSeconds(2);
        PluginBinding binding = new PluginBinding(tenant, Capability.DISBURSE, key, 3);
        var artifact = new PluginControlPlaneRepository.RegisteredArtifact("console-pay-v2.jar", key,
                "b".repeat(64), PluginType.PAYMENT, Set.of(Capability.DISBURSE), "ACTIVE", false);
        var event = new PluginControlPlaneRepository.PluginLifecycleAuditEvent(UUID.randomUUID(), key,
                "PLUGIN_ACTIVATED", "operator", java.time.Instant.EPOCH, "ACTIVE");
        PluginHandle handle = mock(PluginHandle.class);
        when(handle.key()).thenReturn(key);
        when(admin.install("candidate.jar")).thenReturn(handle);
        when(admin.bindings(tenant)).thenReturn(List.of(binding));
        when(admin.artifacts()).thenReturn(List.of(artifact));
        when(admin.tenants()).thenReturn(List.of(new PluginControlPlaneRepository.TenantSummary(tenant, "DEMO")));
        when(admin.auditEvents(key)).thenReturn(List.of(event));
        when(admin.bind(tenant, Capability.DISBURSE, key, 2)).thenReturn(binding);
        when(admin.unbind(tenant, Capability.DISBURSE, 3)).thenReturn(4L);
        when(admin.upgrade(tenant, Capability.DISBURSE, 3, "candidate.jar", timeout))
                .thenReturn(handle);
        when(admin.rollback(tenant, key, Capability.DISBURSE, 4, timeout))
                .thenReturn(handle);
        when(admin.activate(key)).thenReturn(handle);
        when(admin.drain(key, timeout)).thenReturn(false);
        when(admin.artifacts()).thenReturn(List.of(artifact));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "operator", "", List.of(new SimpleGrantedAuthority("ROLE_FINBRIDGE_ADMIN"))));

        assertEquals(List.of(binding), controller.bindings(tenant));
        assertEquals(List.of(artifact), controller.artifacts());
        assertEquals("DEMO", controller.tenants().getFirst().tenantCode());
        assertEquals(List.of(event), controller.audit(key.pluginId(), key.pluginVersion()));
        assertEquals(binding, controller.bind(new AdminConsoleController.BindingRequest(tenant,
                Capability.DISBURSE, key.pluginId(), key.pluginVersion(), 2)));
        assertEquals(4L, controller.unbind(new AdminConsoleController.UnbindRequest(tenant,
                Capability.DISBURSE, 3)));
        assertEquals(artifact, controller.upgrade(new AdminConsoleController.UpgradeRequest(tenant,
                Capability.DISBURSE, 3, "candidate.jar", timeout)));
        assertEquals(artifact, controller.rollback(new AdminConsoleController.RollbackRequest(tenant,
                key.pluginId(), key.pluginVersion(), Capability.DISBURSE, 4, timeout)));
        assertEquals(artifact, controller.activate(key.pluginId(), key.pluginVersion()));
        assertEquals(false, controller.drain(key.pluginId(), key.pluginVersion(), timeout));
        controller.unload(key.pluginId(), key.pluginVersion(), timeout);
        verify(admin).unload(key, timeout);
        verify(admin).drain(key, timeout);
        verify(admin, org.mockito.Mockito.atLeast(3)).artifacts();
    }

    @Test
    void deniesUpgradeRollbackUnbindAuditAndUnloadWithoutAdministrator() throws Exception {
        PluginAdminFacade admin = mock(PluginAdminFacade.class);
        AdminConsoleController controller = new AdminConsoleController(admin);
        UUID tenant = UUID.randomUUID();
        Duration timeout = Duration.ofSeconds(1);

        assertThrows(AccessDeniedException.class, () -> controller.unbind(
                new AdminConsoleController.UnbindRequest(tenant, Capability.DISBURSE, 0)));
        assertThrows(AccessDeniedException.class, () -> controller.upgrade(
                new AdminConsoleController.UpgradeRequest(tenant, Capability.DISBURSE, 0, "candidate.jar", timeout)));
        assertThrows(AccessDeniedException.class, () -> controller.rollback(
                new AdminConsoleController.RollbackRequest(tenant, "pay", "1", Capability.DISBURSE, 0, timeout)));
        assertThrows(AccessDeniedException.class, () -> controller.audit("pay", "1"));
        assertThrows(AccessDeniedException.class, () -> controller.unload("pay", "1", timeout));
        verifyNoInteractions(admin);
    }

    @Test
    void lifecycleResponseFailsClearlyWhenArtifactWasNotPersisted() {
        PluginAdminFacade admin = mock(PluginAdminFacade.class);
        AdminConsoleController controller = new AdminConsoleController(admin);
        PluginKey key = new PluginKey("missing-artifact", "1.0.0");
        PluginHandle handle = mock(PluginHandle.class);
        when(handle.key()).thenReturn(key);
        when(admin.install("candidate.jar")).thenReturn(handle);
        when(admin.artifacts()).thenReturn(List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "operator", "", List.of(new SimpleGrantedAuthority("ROLE_FINBRIDGE_ADMIN"))));

        assertThrows(IllegalStateException.class,
                () -> controller.install(new AdminConsoleController.ArtifactRequest("candidate.jar")));
        verify(admin).artifacts();
    }
}
