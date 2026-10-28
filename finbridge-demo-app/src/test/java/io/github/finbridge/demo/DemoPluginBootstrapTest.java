package io.github.finbridge.demo;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.finbridge.host.PluginAdminFacade;
import io.github.finbridge.host.PluginControlPlaneRepository;
import io.github.finbridge.runtime.PluginBinding;
import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.PluginKey;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.core.context.SecurityContextHolder;

class DemoPluginBootstrapTest {
    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void registersActivatesAndBindsSyntheticFixturesForEachTenant() throws Exception {
        PluginAdminFacade admin = mock(PluginAdminFacade.class);
        when(admin.artifacts()).thenReturn(List.of()).thenReturn(loadedArtifacts());
        when(admin.tenants()).thenReturn(List.of(new PluginControlPlaneRepository.TenantSummary(TENANT_ID, "alpha")));
        when(admin.bindings(TENANT_ID)).thenReturn(List.of());

        new DemoPluginBootstrap(admin).run(new DefaultApplicationArguments(new String[0]));

        verify(admin).install("alphapay-1.0.0-jar-with-dependencies.jar");
        verify(admin).activate(new PluginKey("alphapay", "1.0.0"));
        verify(admin).bind(TENANT_ID, Capability.DISBURSE, new PluginKey("alphapay", "1.0.0"), 0);
        verify(admin).bind(TENANT_ID, Capability.QUERY_STATUS, new PluginKey("alphapay", "1.0.0"), 0);
        verify(admin).bind(TENANT_ID, Capability.CREDIT_SCORE, new PluginKey("creditscorex", "1.0.0"), 0);
        verify(admin).bind(TENANT_ID, Capability.BLACKLIST, new PluginKey("blacklisty", "1.0.0"), 0);
        verify(admin).bind(TENANT_ID, Capability.FRAUD_SIGNAL, new PluginKey("fraudsignalz", "1.0.0"), 0);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void existingActiveArtifactsAndBindingsAreLeftUntouched() throws Exception {
        PluginAdminFacade admin = mock(PluginAdminFacade.class);
        List<PluginControlPlaneRepository.RegisteredArtifact> active = loadedArtifacts().stream()
                .map(artifact -> new PluginControlPlaneRepository.RegisteredArtifact(artifact.artifactName(),
                        artifact.key(), artifact.sha256(), artifact.type(), artifact.capabilities(), "ACTIVE", false))
                .toList();
        when(admin.artifacts()).thenReturn(active);
        when(admin.tenants()).thenReturn(List.of(new PluginControlPlaneRepository.TenantSummary(TENANT_ID, "alpha")));
        when(admin.bindings(TENANT_ID)).thenReturn(activeBindings());

        new DemoPluginBootstrap(admin).run(new DefaultApplicationArguments(new String[0]));

        verify(admin, never()).install(any());
        verify(admin, never()).activate(any());
        verify(admin, never()).bind(any(), any(), any(), anyLong());
    }

    private static List<PluginControlPlaneRepository.RegisteredArtifact> loadedArtifacts() {
        return List.of(
                artifact("alphapay", Set.of(Capability.DISBURSE, Capability.QUERY_STATUS)),
                artifact("creditscorex", Set.of(Capability.CREDIT_SCORE)),
                artifact("blacklisty", Set.of(Capability.BLACKLIST)),
                artifact("fraudsignalz", Set.of(Capability.FRAUD_SIGNAL)));
    }

    private static PluginControlPlaneRepository.RegisteredArtifact artifact(
            String id, Set<Capability> capabilities) {
        return new PluginControlPlaneRepository.RegisteredArtifact(id + "-1.0.0-jar-with-dependencies.jar",
                new PluginKey(id, "1.0.0"), "synthetic-checksum", io.github.finbridge.spi.PluginType.RISK_DATA,
                capabilities, "LOADED", false);
    }

    private static List<PluginBinding> activeBindings() {
        return List.of(
                new PluginBinding(TENANT_ID, Capability.DISBURSE, new PluginKey("alphapay", "1.0.0"), 1),
                new PluginBinding(TENANT_ID, Capability.QUERY_STATUS, new PluginKey("alphapay", "1.0.0"), 1),
                new PluginBinding(TENANT_ID, Capability.CREDIT_SCORE, new PluginKey("creditscorex", "1.0.0"), 1),
                new PluginBinding(TENANT_ID, Capability.BLACKLIST, new PluginKey("blacklisty", "1.0.0"), 1),
                new PluginBinding(TENANT_ID, Capability.FRAUD_SIGNAL, new PluginKey("fraudsignalz", "1.0.0"), 1));
    }
}
