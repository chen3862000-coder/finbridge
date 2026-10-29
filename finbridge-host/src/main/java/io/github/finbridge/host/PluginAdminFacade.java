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

/** Admin-authorized facade for provider lifecycle, tenant routing, and audit operations. */
@Service
public class PluginAdminFacade {
    private final PluginLifecycleManager lifecycle;

    public PluginAdminFacade(PluginLifecycleManager lifecycle) {
        this.lifecycle = lifecycle;
    }

    /** Installs an allowlisted local provider artifact in the loaded state. */
    public PluginHandle install(String artifactName) {
        requireAdmin();
        return lifecycle.install(artifactName);
    }

    /** Activates a previously installed provider. */
    public PluginHandle activate(PluginKey key) {
        requireAdmin();
        return lifecycle.activate(key);
    }

    /** Binds an active provider to a tenant capability using optimistic revision control. */
    public PluginBinding bind(UUID tenantId, Capability capability, PluginKey key,
            long expectedRevision) {
        requireAdmin();
        return lifecycle.bind(tenantId, capability, key, expectedRevision);
    }

    /** Removes a tenant capability binding when its expected revision is current. */
    public long unbind(UUID tenantId, Capability capability, long expectedRevision) {
        requireAdmin();
        return lifecycle.unbind(tenantId, capability, expectedRevision);
    }

    /** Upgrades future routing after safely draining the existing provider version. */
    public PluginHandle upgrade(UUID tenantId, Capability capability, long expectedRevision,
            String artifactName, Duration timeout) throws IOException, InterruptedException {
        requireAdmin();
        return lifecycle.upgrade(tenantId, capability, expectedRevision, artifactName, timeout);
    }

    /** Restores future routing to a selected provider version without changing historical payment owners. */
    public PluginHandle rollback(UUID tenantId, PluginKey key, Capability capability,
            long expectedRevision, Duration timeout) throws IOException, InterruptedException {
        requireAdmin();
        return lifecycle.rollback(tenantId, key, capability, expectedRevision, timeout);
    }

    /** Begins draining a provider; false indicates references or active invocations remain. */
    public boolean drain(PluginKey key, Duration timeout) throws InterruptedException {
        requireAdmin();
        return lifecycle.drain(key, timeout);
    }

    /** Unloads a drained provider only when no binding or unresolved payment retains it. */
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
