package io.github.finbridge.host;

import io.github.finbridge.lending.persistence.LendingAggregateRepository;
import io.github.finbridge.runtime.PluginHandle;
import io.github.finbridge.runtime.PluginLifecycleState;
import io.github.finbridge.runtime.PluginRetentionPolicy;
import io.github.finbridge.runtime.PluginRuntime;
import io.github.finbridge.runtime.PluginBinding;
import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.PluginContext;
import io.github.finbridge.spi.PluginKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class PluginLifecycleManager {
    private final PluginRuntime runtime;
    private final PluginControlPlaneRepository controlPlane;
    private final Path artifactRoot;
    private final PluginRetentionPolicy retention;

    public PluginLifecycleManager(PluginRuntime runtime, PluginControlPlaneRepository controlPlane,
            LendingAggregateRepository lending,
            @org.springframework.beans.factory.annotation.Value("${finbridge.plugin-directory:plugins}")
            Path artifactRoot) {
        PluginRuntime pluginRuntime = Objects.requireNonNull(runtime, "runtime");
        PluginControlPlaneRepository repository = Objects.requireNonNull(controlPlane, "controlPlane");
        LendingAggregateRepository ownerRepository = Objects.requireNonNull(lending, "lending");
        Path normalizedArtifactRoot = Objects.requireNonNull(artifactRoot, "artifactRoot")
                .toAbsolutePath().normalize();
        this.runtime = pluginRuntime;
        this.controlPlane = repository;
        this.artifactRoot = normalizedArtifactRoot;
        this.retention = key -> !ownerRepository.hasUnresolvedPaymentOwner(key);
    }

    public synchronized PluginHandle install(String artifactName) {
        String actor = HostAuthorization.requireAdmin();
        ensureArtifactIsRegisteredCandidate(artifactName);
        PluginHandle handle = runtime.install(artifactName, new PluginContext(java.util.Map.of()));
        String checksum = handle.sha256();
        try {
            ensureRegisteredIdentityAvailable(artifactName);
            controlPlane.registerArtifact(artifactName, handle.key(), checksum,
                    handle.descriptor().pluginType(), handle.descriptor().capabilities(), actor);
            controlPlane.recordEvent(handle.key(), "PLUGIN_LOADED", actor, "PLUGIN_LOADED");
            return handle;
        } catch (RuntimeException failure) {
            try {
                runtime.discardLoaded(handle.key());
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw new IllegalStateException("Plugin installation could not be persisted", failure);
        }
    }

    private void ensureRegisteredIdentityAvailable(String artifactName) {
        controlPlane.registeredArtifactName(artifactName).ifPresent(ignored -> {
            throw new IllegalStateException("Artifact filename is already registered");
        });
    }

    private void ensureArtifactIsRegisteredCandidate(String artifactName) {
        Path normalized = Path.of(artifactName).normalize();
        if (normalized.isAbsolute() || normalized.getNameCount() != 1
                || !normalized.toString().equals(artifactName) || artifactName.isBlank()) {
            throw new IllegalArgumentException("Only allowlisted local artifact filenames are accepted");
        }
        Path candidate = artifactRoot.resolve(normalized).normalize();
        if (!artifactRoot.equals(candidate.getParent()) || !Files.isRegularFile(candidate)) {
            throw new IllegalArgumentException("Artifact is not a prebuilt file in the approved directory");
        }
    }

    public synchronized PluginHandle activate(PluginKey key) {
        String actor = HostAuthorization.requireAdmin();
        PluginHandle handle = runtime.plugin(key);
        ensurePersistedArtifact(handle);
        PluginControlPlaneRepository.RegisteredArtifact artifact = controlPlane.findArtifact(key)
                .orElseThrow(() -> new IllegalStateException("Plugin artifact is not registered"));
        if ("RETIRED".equals(artifact.desiredState())) {
            throw new IllegalStateException("Retired artifact requires rollback to be reactivated");
        }
        handle.activate();
        controlPlane.setDesiredState(key, "ACTIVE", actor, "PLUGIN_ACTIVATED");
        return handle;
    }

    public synchronized PluginBinding bind(UUID tenantId, Capability capability, PluginKey key,
            long expectedRevision) {
        String actor = HostAuthorization.requireAdmin();
        PluginHandle target = runtime.plugin(key);
        if (target.state() != PluginLifecycleState.ACTIVE
                || !target.descriptor().capabilities().contains(capability)) {
            throw new IllegalStateException("Binding target is not active or does not support capability");
        }
        PluginBinding persisted = controlPlane.bindingSwitch(tenantId, capability, key,
                expectedRevision, actor).binding();
        runtime.publishBinding(persisted);
        return persisted;
    }

    public synchronized long unbind(UUID tenantId, Capability capability, long expectedRevision) {
        String actor = HostAuthorization.requireAdmin();
        long revision = controlPlane.removeBinding(tenantId, capability, expectedRevision, actor);
        runtime.publishUnbind(tenantId, capability, revision);
        return revision;
    }

    private void ensurePersistedArtifact(PluginHandle handle) {
        PluginControlPlaneRepository.RegisteredArtifact artifact = controlPlane.findArtifact(handle.key())
                .orElseThrow(() -> new IllegalStateException("Plugin artifact is not registered"));
        if (!artifact.sha256().equals(handle.sha256())) {
            throw new IllegalStateException("Loaded artifact checksum differs from registered identity");
        }
        if (!artifact.capabilities().equals(handle.descriptor().capabilities())
                || artifact.type() != handle.descriptor().pluginType()) {
            throw new IllegalStateException("Loaded artifact descriptor differs from persisted identity");
        }
    }

    public synchronized boolean drain(PluginKey key, Duration timeout) throws InterruptedException {
        String actor = HostAuthorization.requireAdmin();
        controlPlane.ensureNoBindings(key);
        boolean drained = runtime.drain(key, timeout, retention);
        controlPlane.recordEvent(key, "PLUGIN_DRAIN_ATTEMPT", actor,
                drained ? "PLUGIN_DRAINED" : "PLUGIN_DRAIN_BLOCKED");
        return drained;
    }

    public synchronized void unload(PluginKey key, Duration timeout) throws IOException, InterruptedException {
        String actor = HostAuthorization.requireAdmin();
        controlPlane.ensureNoBindings(key);
        if (runtime.plugin(key).state() == PluginLifecycleState.ACTIVE) {
            runtime.drain(key, timeout, retention);
        }
        runtime.unload(key, timeout, retention);
        controlPlane.setDesiredState(key, "RETIRED", actor, "PLUGIN_UNLOADED");
    }

    public synchronized PluginHandle upgrade(UUID tenantId, Capability capability, long expectedRevision,
            String artifactName, Duration timeout) throws IOException, InterruptedException {
        String actor = HostAuthorization.requireAdmin();
        PluginHandle candidate = install(artifactName);
        activate(candidate.key());
        PluginKey previousKey = null;
        PluginControlPlaneRepository.BindingSwitch bindingSwitch;
        try {
            bindingSwitch = controlPlane
                    .updateBindingAndRetirePrevious(tenantId, capability, candidate.key(), expectedRevision, actor);
            previousKey = bindingSwitch.previousKey();
            publishUpgrade(bindingSwitch, candidate, actor);
        } catch (RuntimeException | IOException failure) {
            recoverFailedUpgrade(tenantId, capability, candidate, previousKey, actor, failure);
            throw failure;
        }
        drainPreviousAfterUpgrade(bindingSwitch, candidate, timeout, actor);
        return candidate;
    }

    private void publishUpgrade(PluginControlPlaneRepository.BindingSwitch bindingSwitch,
            PluginHandle candidate, String actor) throws IOException {
        controlPlane.recordEvent(candidate.key(), "PLUGIN_UPGRADE", actor, "PLUGIN_UPGRADE_BOUND");
        runtime.publishBinding(bindingSwitch.binding());
    }

    private void drainPreviousAfterUpgrade(PluginControlPlaneRepository.BindingSwitch bindingSwitch,
            PluginHandle candidate, Duration timeout, String actor) throws IOException, InterruptedException {
        PluginKey previousKey = bindingSwitch.previousKey();
        if (!previousKey.equals(candidate.key()) && controlPlane.bindings(previousKey).isEmpty()) {
            drainRetired(previousKey, timeout, actor);
        }
    }

    private void recoverFailedUpgrade(UUID tenantId, Capability capability, PluginHandle candidate,
            PluginKey previousKey, String actor, Exception failure) {
        restorePreviousBinding(tenantId, capability, candidate, previousKey, actor, failure);
        drainUnboundCandidate(candidate, failure);
    }

    private void restorePreviousBinding(UUID tenantId, Capability capability, PluginHandle candidate,
            PluginKey previousKey, String actor, Exception failure) {
        PluginBinding current = controlPlane.binding(tenantId, capability).orElse(null);
        if (previousKey == null || current == null || !current.pluginKey().equals(candidate.key())) {
            return;
        }
        try {
            PluginControlPlaneRepository.BindingSwitch restored = controlPlane.rollbackBindingAndRestorePrevious(
                    tenantId, capability, previousKey, current.version(), actor);
            runtime.publishBinding(restored.binding());
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    void drainUnboundCandidate(PluginHandle candidate, Exception failure) {
        if (candidate.state() != PluginLifecycleState.ACTIVE || !controlPlane.bindings(candidate.key()).isEmpty()) {
            return;
        }
        try {
            drain(candidate.key(), Duration.ofMillis(1));
        } catch (InterruptedException interrupted) {
            failure.addSuppressed(interrupted);
            Thread.currentThread().interrupt();
        }
    }

    public synchronized PluginHandle rollback(UUID tenantId, PluginKey target, Capability capability,
            long expectedRevision, Duration timeout) throws IOException, InterruptedException {
        String actor = HostAuthorization.requireAdmin();
        PluginHandle targetHandle = loadRollbackTarget(target);
        ensurePersistedArtifact(targetHandle);
        if (targetHandle.state() == PluginLifecycleState.DRAINING) {
            runtime.resumeFromDrain(target);
        } else if (targetHandle.state() == PluginLifecycleState.LOADED) {
            runtime.activate(target);
        }
        PluginControlPlaneRepository.BindingSwitch bindingSwitch = controlPlane.rollbackBindingAndRestorePrevious(tenantId,
                capability, target, expectedRevision, actor);
        runtime.publishBinding(bindingSwitch.binding());
        if (!bindingSwitch.previousKey().equals(target)
                && controlPlane.bindings(bindingSwitch.previousKey()).isEmpty()) {
            drainRetired(bindingSwitch.previousKey(), timeout, actor);
        }
        controlPlane.recordEvent(target, "PLUGIN_ROLLBACK", actor, "PLUGIN_ROLLBACK_BOUND");
        return targetHandle;
    }

    private PluginHandle loadRollbackTarget(PluginKey target) throws IOException {
        try {
            return runtime.plugin(target);
        } catch (IllegalArgumentException missingLoadedVersion) {
            PluginControlPlaneRepository.RegisteredArtifact artifact = controlPlane.findArtifact(target)
                    .orElseThrow(() -> new IllegalStateException("Rollback target is not registered"));
            ensureArtifactIsRegisteredCandidate(artifact.artifactName());
            PluginHandle loaded = runtime.install(artifact.artifactName(),
                    new PluginContext(java.util.Map.of()));
            if (loaded.sha256().equals(artifact.sha256())) {
                return loaded;
            }
            runtime.discardLoaded(target);
            throw new IllegalStateException("Rollback target checksum differs from registered artifact",
                    missingLoadedVersion);
        }
    }

    private void drainRetired(PluginKey key, Duration timeout, String actor)
            throws InterruptedException, IOException {
        if (runtime.plugin(key).state() == PluginLifecycleState.UNLOADED) {
            return;
        }
        if (runtime.plugin(key).state() == PluginLifecycleState.DRAINING) {
            if (!controlPlane.bindings(key).isEmpty()) {
                controlPlane.recordEvent(key, "PLUGIN_DRAIN_BLOCKED", actor, "PLUGIN_STILL_BOUND");
                return;
            }
            if (!retention.canUnload(key)) {
                controlPlane.recordEvent(key, "PLUGIN_DRAIN_BLOCKED", actor, "PLUGIN_RETAINED");
                return;
            }
            if (!runtime.plugin(key).awaitDrained(timeout)) {
                controlPlane.recordEvent(key, "PLUGIN_DRAIN_BLOCKED", actor, "PLUGIN_INVOCATIONS_ACTIVE");
                return;
            }
            runtime.unload(key, timeout, retention);
            controlPlane.setDesiredState(key, "RETIRED", actor, "PLUGIN_UNLOADED");
            return;
        }
        if (!controlPlane.bindings(key).isEmpty()) {
            controlPlane.recordEvent(key, "PLUGIN_DRAIN_BLOCKED", actor, "PLUGIN_STILL_BOUND");
            return;
        }
        if (runtime.plugin(key).state() != PluginLifecycleState.ACTIVE) {
            return;
        }
        if (!runtime.drain(key, timeout, retention)) {
            controlPlane.recordEvent(key, "PLUGIN_DRAIN_BLOCKED", actor, "PLUGIN_INVOCATIONS_ACTIVE");
            return;
        }
        if (!retention.canUnload(key)) {
            controlPlane.recordEvent(key, "PLUGIN_DRAIN_BLOCKED", actor, "PLUGIN_RETAINED");
            return;
        }
        runtime.unload(key, timeout, retention);
        controlPlane.setDesiredState(key, "RETIRED", actor, "PLUGIN_UNLOADED");
    }

    public void restoreAtStartup() {
        String actor = "SYSTEM_STARTUP";
        for (PluginControlPlaneRepository.RegisteredArtifact artifact : controlPlane.restorableArtifacts()) {
            restoreArtifact(artifact, actor);
        }
        for (var binding : controlPlane.bindings()) {
            restoreBinding(binding, actor);
        }
    }

    private void restoreArtifact(PluginControlPlaneRepository.RegisteredArtifact artifact, String actor) {
        boolean retained = artifact.retainedWork();
        if ("RETIRED".equals(artifact.desiredState()) && !retained) {
            return;
        }
        try {
            if (!artifactFileExists(artifact)) {
                controlPlane.recordEvent(artifact.key(), "PLUGIN_RESTORE_FAILED", actor, "ARTIFACT_MISSING");
                return;
            }
            PluginHandle handle = runtime.install(artifact.artifactName(), new PluginContext(java.util.Map.of()));
            if (!artifactIdentityMatches(artifact, handle)) {
                discardLoaded(handle);
                controlPlane.recordEvent(artifact.key(), "PLUGIN_RESTORE_FAILED", actor,
                        "ARTIFACT_IDENTITY_MISMATCH");
                return;
            }
            activateRestoredArtifact(artifact, retained, actor);
        } catch (RuntimeException | IOException failure) {
            controlPlane.recordEvent(artifact.key(), "PLUGIN_RESTORE_FAILED", actor, "PLUGIN_RESTORE_FAILED");
        }
    }

    private boolean artifactFileExists(PluginControlPlaneRepository.RegisteredArtifact artifact) {
        Path file = artifactRoot.resolve(artifact.artifactName()).normalize();
        return artifactRoot.equals(file.getParent()) && Files.isRegularFile(file);
    }

    private boolean artifactIdentityMatches(PluginControlPlaneRepository.RegisteredArtifact artifact,
            PluginHandle handle) {
        return handle.sha256().equals(artifact.sha256())
                && handle.descriptor().pluginType() == artifact.type()
                && handle.descriptor().capabilities().equals(artifact.capabilities());
    }

    private void discardLoaded(PluginHandle handle) throws IOException {
        if (handle.state() == PluginLifecycleState.LOADED) {
            runtime.discardLoaded(handle.key());
        }
    }

    private void activateRestoredArtifact(PluginControlPlaneRepository.RegisteredArtifact artifact,
            boolean retained, String actor) throws IOException {
        PluginHandle handle = runtime.plugin(artifact.key());
        if ("RETIRED".equals(artifact.desiredState())) {
            restoreRetiredArtifact(handle, retained, actor);
            return;
        }
        if (handle.state() == PluginLifecycleState.ACTIVE) {
            controlPlane.recordEvent(artifact.key(), "PLUGIN_RESTORED", actor, "PLUGIN_ALREADY_ACTIVE");
            return;
        }
        runtime.activate(artifact.key());
        controlPlane.recordEvent(artifact.key(), "PLUGIN_RESTORED", actor, "PLUGIN_RESTORED");
    }

    private void restoreRetiredArtifact(PluginHandle handle, boolean retained, String actor) throws IOException {
        runtime.activate(handle.key());
        if (retained) {
            handle.beginDrain();
            controlPlane.recordEvent(handle.key(), "PLUGIN_RESTORED", actor,
                    "RETIRED_OWNER_ARTIFACT_RESTORED_FOR_RECONCILIATION");
        } else {
            runtime.discardLoaded(handle.key());
        }
    }

    private void restoreBinding(PluginBinding binding, String actor) {
        try {
            PluginHandle handle = runtime.plugin(binding.pluginKey());
            if (handle.state() != PluginLifecycleState.ACTIVE
                    || !handle.descriptor().capabilities().contains(binding.capability())) {
                throw new IllegalStateException("Persisted binding target is not active and capable");
            }
            runtime.restoreBinding(binding.tenantId(), binding.capability(), binding.pluginKey(), binding.version());
        } catch (RuntimeException failure) {
            controlPlane.recordEvent(binding.pluginKey(), "BINDING_RESTORE_FAILED", actor,
                    "BINDING_RESTORE_FAILED");
        }
    }

    public List<PluginControlPlaneRepository.PluginLifecycleAuditEvent> events(PluginKey key) {
        HostAuthorization.requireAdministrator();
        return controlPlane.lifecycleEvents(key);
    }

    public List<PluginBinding> bindings(UUID tenantId) {
        HostAuthorization.requireAdministrator();
        return controlPlane.bindings(tenantId);
    }

    public List<PluginControlPlaneRepository.RegisteredArtifact> artifacts() {
        HostAuthorization.requireAdministrator();
        return controlPlane.artifacts();
    }

    public List<PluginControlPlaneRepository.TenantSummary> tenants() {
        HostAuthorization.requireAdministrator();
        return controlPlane.tenants();
    }

}
