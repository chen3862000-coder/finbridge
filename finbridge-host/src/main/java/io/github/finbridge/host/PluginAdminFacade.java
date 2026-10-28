package io.github.finbridge.host;

import io.github.finbridge.runtime.PluginBinding;
import io.github.finbridge.runtime.PluginHandle;
import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.PluginKey;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PluginAdminFacade {
    private final PluginLifecycleManager lifecycle;

    public PluginAdminFacade(PluginLifecycleManager lifecycle) {
        this.lifecycle = lifecycle;
    }

    public PluginHandle install(String artifactName) {
        requireAdmin();
        return lifecycle.install(artifactName);
    }

    public PluginHandle activate(PluginKey key) {
        requireAdmin();
        return lifecycle.activate(key);
    }

    public PluginBinding bind(UUID tenantId, Capability capability, PluginKey key,
            long expectedRevision) {
        requireAdmin();
        return lifecycle.bind(tenantId, capability, key, expectedRevision);
    }

    public long unbind(UUID tenantId, Capability capability, long expectedRevision) {
        requireAdmin();
        return lifecycle.unbind(tenantId, capability, expectedRevision);
    }

    public PluginHandle upgrade(UUID tenantId, Capability capability, long expectedRevision,
            String artifactName, Duration timeout) throws IOException, InterruptedException {
        requireAdmin();
        return lifecycle.upgrade(tenantId, capability, expectedRevision, artifactName, timeout);
    }

    public PluginHandle rollback(UUID tenantId, PluginKey key, Capability capability,
            long expectedRevision, Duration timeout) throws IOException, InterruptedException {
        requireAdmin();
        return lifecycle.rollback(tenantId, key, capability, expectedRevision, timeout);
    }

    public boolean drain(PluginKey key, Duration timeout) throws InterruptedException {
        requireAdmin();
        return lifecycle.drain(key, timeout);
    }

    public void unload(PluginKey key, Duration timeout) throws IOException, InterruptedException {
        requireAdmin();
        lifecycle.unload(key, timeout);
    }

    public List<PluginBinding> bindings(UUID tenantId) {
        requireAdmin();
        return lifecycle.bindings(tenantId);
    }

    public List<PluginControlPlaneRepository.RegisteredArtifact> artifacts() {
        requireAdmin();
        return lifecycle.artifacts();
    }

    public List<PluginControlPlaneRepository.TenantSummary> tenants() {
        requireAdmin();
        return lifecycle.tenants();
    }

    public List<PluginControlPlaneRepository.PluginLifecycleAuditEvent> auditEvents(PluginKey key) {
        requireAdmin();
        return lifecycle.events(key);
    }

    private static String requireAdmin() {
        return HostAuthorization.requireAdmin();
    }

}
