package io.github.finbridge.host;

import io.github.finbridge.runtime.PluginBinding;
import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.PluginKey;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/admin/plugins")
public class AdminConsoleController {
    private final PluginAdminFacade admin;

    public AdminConsoleController(PluginAdminFacade admin) {
        this.admin = admin;
    }

    @GetMapping
    public List<PluginControlPlaneRepository.RegisteredArtifact> artifacts() {
        requireAdmin();
        return admin.artifacts();
    }

    @GetMapping("/tenants")
    public List<PluginControlPlaneRepository.TenantSummary> tenants() {
        requireAdmin();
        return admin.tenants();
    }

    @GetMapping("/bindings")
    public List<PluginBinding> bindings(@RequestParam UUID tenantId) {
        requireAdmin();
        return admin.bindings(tenantId);
    }

    @GetMapping("/{pluginId}/{version}/audit")
    public List<PluginControlPlaneRepository.PluginLifecycleAuditEvent> audit(
            @PathVariable String pluginId, @PathVariable String version) {
        requireAdmin();
        return admin.auditEvents(new PluginKey(pluginId, version));
    }

    @PostMapping
    public PluginControlPlaneRepository.RegisteredArtifact install(@RequestBody ArtifactRequest request) {
        requireAdmin();
        return registered(admin.install(request.artifactName()).key());
    }

    @PostMapping("/upgrade")
    public PluginControlPlaneRepository.RegisteredArtifact upgrade(@RequestBody UpgradeRequest request)
            throws IOException, InterruptedException {
        requireAdmin();
        PluginKey key = admin.upgrade(request.tenantId(), request.capability(), request.expectedRevision(),
                request.artifactName(), request.timeout()).key();
        return registered(key);
    }

    @PostMapping("/rollback")
    public PluginControlPlaneRepository.RegisteredArtifact rollback(@RequestBody RollbackRequest request)
            throws IOException, InterruptedException {
        requireAdmin();
        PluginKey key = new PluginKey(request.pluginId(), request.pluginVersion());
        admin.rollback(request.tenantId(), key, request.capability(), request.expectedRevision(), request.timeout());
        return registered(key);
    }

    @PostMapping("/unbind")
    public long unbind(@RequestBody UnbindRequest request) {
        requireAdmin();
        return admin.unbind(request.tenantId(), request.capability(), request.expectedRevision());
    }

    @PostMapping("/{pluginId}/{version}/activate")
    public PluginControlPlaneRepository.RegisteredArtifact activate(@PathVariable String pluginId,
            @PathVariable String version) {
        requireAdmin();
        PluginKey key = new PluginKey(pluginId, version);
        admin.activate(key);
        return registered(key);
    }

    @PutMapping("/bindings")
    public PluginBinding bind(@RequestBody BindingRequest request) {
        requireAdmin();
        return admin.bind(request.tenantId(), request.capability(),
                new PluginKey(request.pluginId(), request.pluginVersion()), request.expectedRevision());
    }

    @PostMapping("/{pluginId}/{version}/drain")
    public boolean drain(@PathVariable String pluginId, @PathVariable String version,
            @RequestParam(defaultValue = "PT0S") Duration timeout) throws InterruptedException {
        requireAdmin();
        return admin.drain(new PluginKey(pluginId, version), timeout);
    }

    @PostMapping("/{pluginId}/{version}/unload")
    public void unload(@PathVariable String pluginId, @PathVariable String version,
            @RequestParam(defaultValue = "PT0S") Duration timeout) throws IOException, InterruptedException {
        requireAdmin();
        admin.unload(new PluginKey(pluginId, version), timeout);
    }

    public record ArtifactRequest(String artifactName) { }

    public record UpgradeRequest(UUID tenantId, Capability capability, long expectedRevision,
            String artifactName, Duration timeout) { }

    public record RollbackRequest(UUID tenantId, String pluginId, String pluginVersion,
            Capability capability, long expectedRevision, Duration timeout) { }

    public record UnbindRequest(UUID tenantId, Capability capability, long expectedRevision) { }

    public record BindingRequest(UUID tenantId, Capability capability, String pluginId,
            String pluginVersion, long expectedRevision) { }

    private PluginControlPlaneRepository.RegisteredArtifact registered(PluginKey key) {
        return admin.artifacts().stream().filter(artifact -> artifact.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Plugin artifact was not persisted"));
    }

    private static void requireAdmin() {
        HostAuthorization.requireAdmin();
    }
}
