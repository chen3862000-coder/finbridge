package io.github.finbridge.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;

import io.github.finbridge.lending.persistence.LendingAggregateRepository;
import io.github.finbridge.runtime.PluginBinding;
import io.github.finbridge.runtime.PluginHandle;
import io.github.finbridge.runtime.PluginLifecycleState;
import io.github.finbridge.runtime.PluginRuntime;
import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.spi.PluginType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class PluginLifecycleManagerTest {
    private static final String ARTIFACT = "creditscorex-0.1.0-SNAPSHOT-jar-with-dependencies.jar";
    private static final String SECOND_RISK_ARTIFACT = "blacklisty-0.1.0-SNAPSHOT-jar-with-dependencies.jar";
    private static final Capability CAPABILITY = Capability.CREDIT_SCORE;

    @TempDir
    Path artifactRoot;

    private PluginRuntime runtime;
    private PluginControlPlaneRepository controlPlane;
    private LendingAggregateRepository lending;
    private PluginLifecycleManager lifecycle;

    @BeforeEach
    void setUp() throws Exception {
        Path source = Path.of(System.getProperty("finbridge.root"), "plugins", "creditscorex", "target", ARTIFACT);
        Files.copy(source, artifactRoot.resolve(ARTIFACT));
        Path secondRiskSource = Path.of(System.getProperty("finbridge.root"), "plugins", "blacklisty", "target",
                SECOND_RISK_ARTIFACT);
        Files.copy(secondRiskSource, artifactRoot.resolve(SECOND_RISK_ARTIFACT));
        runtime = new PluginRuntime(artifactRoot, new ProviderFreeParent(getClass().getClassLoader()), 1);
        controlPlane = mock(PluginControlPlaneRepository.class);
        lending = mock(LendingAggregateRepository.class);
        lifecycle = new PluginLifecycleManager(runtime, controlPlane, lending, artifactRoot);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "operator", "", List.of(new SimpleGrantedAuthority("ROLE_FINBRIDGE_ADMIN"))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void installsValidArtifactAndPersistsItsVerifiedIdentity() {
        when(controlPlane.registeredArtifactName(ARTIFACT)).thenReturn(Optional.empty());

        PluginHandle handle = lifecycle.install(ARTIFACT);

        assertEquals(PluginLifecycleState.LOADED, handle.state());
        verify(controlPlane).registerArtifact(eq(ARTIFACT), eq(handle.key()), eq(handle.sha256()),
                eq(PluginType.RISK_DATA), eq(Set.of(CAPABILITY)), eq("operator"));
        verify(controlPlane).recordEvent(handle.key(), "PLUGIN_LOADED", "operator", "PLUGIN_LOADED");
    }

    @Test
    void rejectsPathTraversalBeforeRuntimeLoad() {
        assertThrows(IllegalArgumentException.class, () -> lifecycle.install("../pom.xml"));
        assertTrue(runtime.plugins().isEmpty());
    }

    @Test
    void installDiscardsLoadedPluginWhenArtifactNameWasAlreadyRegistered() {
        PluginKey existing = new PluginKey("existing-risk", "1.0.0");
        when(controlPlane.registeredArtifactName(ARTIFACT)).thenReturn(Optional.of(
                new PluginControlPlaneRepository.RegisteredArtifact(ARTIFACT, existing, "a".repeat(64),
                        PluginType.RISK_DATA, Set.of(CAPABILITY), "LOADED", false)));

        assertThrows(IllegalStateException.class, () -> lifecycle.install(ARTIFACT));

        assertTrue(runtime.plugins().isEmpty());
        verify(controlPlane, never()).registerArtifact(eq(ARTIFACT), any(), any(), any(), any(), any());
    }

    @Test
    void installRejectsMissingFilesAndClosesLoadedPluginWhenRegistrationFails() {
        assertThrows(IllegalArgumentException.class, () -> lifecycle.install("missing.jar"));
        when(controlPlane.registeredArtifactName(ARTIFACT)).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("synthetic database failure"))
                .when(controlPlane).registerArtifact(eq(ARTIFACT), any(), any(), any(), any(), eq("operator"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> lifecycle.install(ARTIFACT));

        assertEquals("Plugin installation could not be persisted", failure.getMessage());
        assertTrue(runtime.plugins().isEmpty());
        assertEquals(1, failure.getCause() instanceof IllegalStateException ? 1 : 0);
    }

    @Test
    void activationRequiresPersistedIdentityAndPersistsDesiredState() {
        PluginHandle handle = installedHandle();
        when(controlPlane.findArtifact(handle.key())).thenReturn(Optional.of(artifact(handle, "LOADED", false)));

        assertEquals(PluginLifecycleState.ACTIVE, lifecycle.activate(handle.key()).state());

        verify(controlPlane).setDesiredState(handle.key(), "ACTIVE", "operator", "PLUGIN_ACTIVATED");
    }

    @Test
    void activationRejectsPersistedChecksumMismatch() {
        PluginHandle handle = installedHandle();
        var persisted = new PluginControlPlaneRepository.RegisteredArtifact(ARTIFACT, handle.key(),
                "0".repeat(64), PluginType.RISK_DATA, Set.of(CAPABILITY), "LOADED", false);
        when(controlPlane.findArtifact(handle.key())).thenReturn(Optional.of(persisted));

        assertThrows(IllegalStateException.class, () -> lifecycle.activate(handle.key()));
        assertEquals(PluginLifecycleState.LOADED, handle.state());
        verify(controlPlane, never()).setDesiredState(handle.key(), "ACTIVE", "operator", "PLUGIN_ACTIVATED");
    }

    @Test
    void activationFailsWhenLoadedPluginHasNoPersistedArtifact() {
        PluginHandle handle = runtime.install(ARTIFACT,
                new io.github.finbridge.spi.PluginContext(java.util.Map.of()));
        when(controlPlane.findArtifact(handle.key())).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> lifecycle.activate(handle.key()));
        assertEquals(PluginLifecycleState.LOADED, handle.state());
    }

    @Test
    void activationFailsWhenArtifactDisappearsAfterIdentityVerification() {
        PluginHandle handle = runtime.install(ARTIFACT,
                new io.github.finbridge.spi.PluginContext(java.util.Map.of()));
        when(controlPlane.findArtifact(handle.key())).thenReturn(Optional.of(artifact(handle, "LOADED", false)),
                Optional.empty());

        assertThrows(IllegalStateException.class, () -> lifecycle.activate(handle.key()));
        assertEquals(PluginLifecycleState.LOADED, handle.state());
    }

    @Test
    void activationRejectsRetiredArtifactAndDescriptorMismatch() {
        PluginHandle handle = installedHandle();
        when(controlPlane.findArtifact(handle.key())).thenReturn(Optional.of(artifact(handle, "RETIRED", false)));
        assertThrows(IllegalStateException.class, () -> lifecycle.activate(handle.key()));

        var descriptorMismatch = new PluginControlPlaneRepository.RegisteredArtifact(ARTIFACT, handle.key(),
                handle.sha256(), PluginType.RISK_DATA, Set.of(Capability.FRAUD_SIGNAL), "LOADED", false);
        when(controlPlane.findArtifact(handle.key())).thenReturn(Optional.of(descriptorMismatch));
        assertThrows(IllegalStateException.class, () -> lifecycle.activate(handle.key()));
        assertEquals(PluginLifecycleState.LOADED, handle.state());
    }

    @Test
    void bindingPublishesOnlyAfterDurableControlPlaneSwitch() {
        PluginHandle handle = installedActiveHandle();
        UUID tenant = UUID.randomUUID();
        PluginBinding binding = new PluginBinding(tenant, CAPABILITY, handle.key(), 1);
        when(controlPlane.bindingSwitch(tenant, CAPABILITY, handle.key(), 0, "operator"))
                .thenReturn(new PluginControlPlaneRepository.BindingSwitch(binding, handle.key()));

        assertEquals(binding, lifecycle.bind(tenant, CAPABILITY, handle.key(), 0));
        assertEquals(binding, runtime.binding(tenant, CAPABILITY));
    }

    @Test
    void unbindPublishesPersistedRevisionAndDrainHonorsRetainedOwnerWork() throws Exception {
        PluginHandle handle = installedActiveHandle();
        UUID tenant = UUID.randomUUID();
        runtime.restoreBinding(tenant, CAPABILITY, handle.key(), 1);
        when(controlPlane.removeBinding(tenant, CAPABILITY, 1, "operator")).thenReturn(2L);
        when(controlPlane.bindings(handle.key())).thenReturn(List.of());
        when(lending.hasUnresolvedPaymentOwner(handle.key())).thenReturn(true);

        assertEquals(2L, lifecycle.unbind(tenant, CAPABILITY, 1));
        assertThrows(IllegalArgumentException.class, () -> runtime.binding(tenant, CAPABILITY));
        assertFalse(lifecycle.drain(handle.key(), Duration.ZERO));
        assertEquals(PluginLifecycleState.DRAINING, handle.state());
        verify(controlPlane).recordEvent(handle.key(), "PLUGIN_DRAIN_ATTEMPT", "operator", "PLUGIN_DRAIN_BLOCKED");
    }

    @Test
    void unboundPluginDrainsAndUnloadsWhenNoPaymentOwnerWorkRemains() throws Exception {
        PluginHandle handle = installedActiveHandle();
        when(controlPlane.bindings(handle.key())).thenReturn(List.of());
        when(lending.hasUnresolvedPaymentOwner(handle.key())).thenReturn(false);

        assertTrue(lifecycle.drain(handle.key(), Duration.ZERO));
        assertEquals(PluginLifecycleState.DRAINING, handle.state());
        lifecycle.unload(handle.key(), Duration.ZERO);

        assertTrue(runtime.plugins().isEmpty());
        verify(controlPlane).setDesiredState(handle.key(), "RETIRED", "operator", "PLUGIN_UNLOADED");
    }

    @Test
    void upgradeCommitsFutureBindingRevisionAndRetiresPreviousOwner() throws Exception {
        PluginHandle candidate = installedHandle();
        PluginHandle previous = runtime.install(SECOND_RISK_ARTIFACT,
                new io.github.finbridge.spi.PluginContext(java.util.Map.of()));
        runtime.activate(previous.key());
        UUID tenant = UUID.randomUUID();
        PluginBinding newBinding = new PluginBinding(tenant, CAPABILITY, candidate.key(), 2);
        when(controlPlane.findArtifact(candidate.key())).thenReturn(Optional.of(artifact(candidate, "LOADED", false)));
        when(controlPlane.findArtifact(previous.key())).thenReturn(Optional.of(artifact(previous, "ACTIVE", false)));
        when(controlPlane.updateBindingAndRetirePrevious(tenant, CAPABILITY, candidate.key(), 1, "operator"))
                .thenReturn(new PluginControlPlaneRepository.BindingSwitch(newBinding,
                        previous.key()));
        when(controlPlane.bindings(previous.key())).thenReturn(List.of());
        when(controlPlane.bindings(candidate.key())).thenReturn(List.of(newBinding));
        when(lending.hasUnresolvedPaymentOwner(previous.key())).thenReturn(false);

        assertEquals(candidate, lifecycle.upgrade(tenant, CAPABILITY, 1, ARTIFACT, Duration.ZERO));

        assertEquals(PluginLifecycleState.ACTIVE, candidate.state());
        verify(controlPlane).recordEvent(candidate.key(), "PLUGIN_UPGRADE", "operator", "PLUGIN_UPGRADE_BOUND");
        assertEquals(PluginLifecycleState.UNLOADED, previous.state());
    }

    @Test
    void upgradeRestoresPreviousBindingAndDrainsCandidateWhenAuditWriteFails() throws Exception {
        PluginHandle candidate = installedHandle();
        PluginHandle previous = runtime.install(SECOND_RISK_ARTIFACT,
                new io.github.finbridge.spi.PluginContext(java.util.Map.of()));
        runtime.activate(previous.key());
        UUID tenant = UUID.randomUUID();
        Capability capability = Capability.BLACKLIST;
        runtime.restoreBinding(tenant, capability, previous.key(), 1);
        PluginBinding switched = new PluginBinding(tenant, capability, candidate.key(), 2);
        when(controlPlane.findArtifact(candidate.key())).thenReturn(Optional.of(artifact(candidate, "LOADED", false)));
        when(controlPlane.updateBindingAndRetirePrevious(tenant, capability, candidate.key(), 1, "operator"))
                .thenReturn(new PluginControlPlaneRepository.BindingSwitch(switched, previous.key()));
        when(controlPlane.bindings(candidate.key())).thenReturn(List.of());
        doThrow(new IllegalStateException("synthetic audit write failure"))
                .when(controlPlane).recordEvent(candidate.key(), "PLUGIN_UPGRADE", "operator", "PLUGIN_UPGRADE_BOUND");
        when(controlPlane.binding(tenant, capability)).thenReturn(Optional.of(switched));
        when(controlPlane.rollbackBindingAndRestorePrevious(tenant, capability, previous.key(), 2, "operator"))
                .thenReturn(new PluginControlPlaneRepository.BindingSwitch(
                        new PluginBinding(tenant, capability, previous.key(), 3), candidate.key()));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> lifecycle.upgrade(tenant, capability, 1, ARTIFACT, Duration.ZERO));

        assertEquals(PluginLifecycleState.DRAINING, candidate.state());
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(PluginLifecycleState.ACTIVE, previous.state());
        assertEquals(previous.key(), runtime.binding(tenant, capability).pluginKey());
    }

    @Test
    void failedUpgradeDrainsUnboundCandidateAndPreservesCurrentBinding() throws Exception {
        PluginHandle candidate = installedHandle();
        UUID tenant = UUID.randomUUID();
        when(controlPlane.findArtifact(candidate.key())).thenReturn(Optional.of(artifact(candidate, "LOADED", false)));
        when(controlPlane.updateBindingAndRetirePrevious(tenant, CAPABILITY, candidate.key(), 8, "operator"))
                .thenThrow(new IllegalStateException("stale durable revision"));
        when(controlPlane.binding(tenant, CAPABILITY)).thenReturn(Optional.empty());
        when(controlPlane.bindings(candidate.key())).thenReturn(List.of());

        assertThrows(IllegalStateException.class,
                () -> lifecycle.upgrade(tenant, CAPABILITY, 8, ARTIFACT, Duration.ZERO));

        assertEquals(PluginLifecycleState.DRAINING, candidate.state());
        verify(controlPlane).updateBindingAndRetirePrevious(tenant, CAPABILITY, candidate.key(), 8, "operator");
    }

    @Test
    void failedUpgradeRestoresInterruptWhenCandidateDrainIsInterrupted() throws Exception {
        PluginHandle candidate = installedActiveHandle();
        IllegalStateException failure = new IllegalStateException("synthetic upgrade failure");

        try (var lease = candidate.acquire(io.github.finbridge.runtime.InvocationKind.BUSINESS)) {
            assertSame(candidate, lease.handle());
            Thread.currentThread().interrupt();
            lifecycle.drainUnboundCandidate(candidate, failure);

            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, failure.getSuppressed().length);
            assertTrue(failure.getSuppressed()[0] instanceof InterruptedException);
            assertEquals(PluginLifecycleState.DRAINING, candidate.state());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rollbackLoadsRegisteredTargetAndPublishesRestoredRevision() throws Exception {
        PluginHandle original = runtime.install(ARTIFACT,
                new io.github.finbridge.spi.PluginContext(java.util.Map.of()));
        runtime.activate(original.key());
        runtime.drain(original.key(), Duration.ZERO, ignored -> true);
        runtime.unload(original.key(), Duration.ZERO, ignored -> true);
        UUID tenant = UUID.randomUUID();
        PluginBinding rollbackBinding = new PluginBinding(tenant, CAPABILITY, original.key(), 3);
        var retired = artifact(original, "RETIRED", false);
        when(controlPlane.findArtifact(original.key())).thenReturn(Optional.of(retired));
        when(controlPlane.rollbackBindingAndRestorePrevious(tenant, CAPABILITY, original.key(), 2, "operator"))
                .thenReturn(new PluginControlPlaneRepository.BindingSwitch(rollbackBinding, original.key()));

        PluginHandle target = lifecycle.rollback(tenant, original.key(), CAPABILITY, 2, Duration.ZERO);

        assertEquals(original.key(), target.key());
        assertEquals(PluginLifecycleState.ACTIVE, target.state());
        verify(controlPlane).recordEvent(target.key(), "PLUGIN_ROLLBACK", "operator", "PLUGIN_ROLLBACK_BOUND");
    }

    @Test
    void rollbackLoadsArtifactFromApprovedDirectoryWhenTargetIsNotInRuntime() throws Exception {
        PluginKey target = new PluginKey("creditscorex", "1.0.0");
        PluginHandle inspected = runtime.install(ARTIFACT,
                new io.github.finbridge.spi.PluginContext(java.util.Map.of()));
        PluginBinding rollbackBinding = new PluginBinding(UUID.randomUUID(), CAPABILITY, target, 3);
        when(controlPlane.findArtifact(target)).thenReturn(Optional.of(artifact(inspected, "RETIRED", false)));
        when(controlPlane.rollbackBindingAndRestorePrevious(eq(rollbackBinding.tenantId()), eq(CAPABILITY),
                eq(target), eq(2L), eq("operator")))
                .thenReturn(new PluginControlPlaneRepository.BindingSwitch(rollbackBinding, target));

        PluginHandle loaded = lifecycle.rollback(rollbackBinding.tenantId(), target, CAPABILITY, 2,
                Duration.ZERO);

        assertEquals(target, loaded.key());
        assertEquals(PluginLifecycleState.ACTIVE, loaded.state());
    }

    @Test
    void rollbackDiscardsNewlyLoadedArtifactWhenPersistedChecksumDoesNotMatch() throws Exception {
        PluginKey target = new PluginKey("creditscorex", "1.0.0");
        when(controlPlane.findArtifact(target)).thenReturn(Optional.of(new PluginControlPlaneRepository.RegisteredArtifact(
                ARTIFACT, target, "0".repeat(64), PluginType.RISK_DATA, Set.of(CAPABILITY), "RETIRED", false)));

        assertThrows(IllegalStateException.class,
                () -> lifecycle.rollback(UUID.randomUUID(), target, CAPABILITY, 2, Duration.ZERO));

        assertThrows(IllegalArgumentException.class, () -> runtime.plugin(target));
        assertTrue(runtime.plugins().isEmpty());
    }

    @Test
    void rollbackRejectsTargetMissingFromRuntimeAndControlPlane() {
        PluginKey missing = new PluginKey("unregistered-risk", "1.0.0");
        when(controlPlane.findArtifact(missing)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> lifecycle.rollback(UUID.randomUUID(), missing, CAPABILITY, 1, Duration.ZERO));
    }

    @Test
    void drainingRetiredProviderWaitsForReconciliationLeaseAndResumesAfterItCloses() throws Exception {
        PluginHandle candidate = installedHandle();
        PluginHandle previous = runtime.install(SECOND_RISK_ARTIFACT,
                new io.github.finbridge.spi.PluginContext(java.util.Map.of()));
        runtime.activate(previous.key());
        runtime.drain(previous.key(), Duration.ZERO, ignored -> true);
        UUID tenant = UUID.randomUUID();
        PluginBinding newBinding = new PluginBinding(tenant, CAPABILITY, candidate.key(), 2);
        when(controlPlane.findArtifact(candidate.key())).thenReturn(Optional.of(artifact(candidate, "LOADED", false)));
        when(controlPlane.updateBindingAndRetirePrevious(tenant, CAPABILITY, candidate.key(), 1, "operator"))
                .thenReturn(new PluginControlPlaneRepository.BindingSwitch(newBinding, previous.key()));
        when(controlPlane.bindings(previous.key())).thenReturn(List.of());
        when(controlPlane.bindings(candidate.key())).thenReturn(List.of(newBinding));
        org.mockito.Mockito.doReturn(true).when(lending).hasUnresolvedPaymentOwner(previous.key());

        lifecycle.upgrade(tenant, CAPABILITY, 1, ARTIFACT, Duration.ZERO);

        assertEquals(PluginLifecycleState.DRAINING, previous.state());
        verify(controlPlane).recordEvent(previous.key(), "PLUGIN_DRAIN_BLOCKED", "operator", "PLUGIN_RETAINED");
    }

    @Test
    void retiredProviderWithNoRetainedWorkUnloadsAfterSuccessfulDrain() throws Exception {
        PluginHandle candidate = installedHandle();
        PluginHandle previous = runtime.install(SECOND_RISK_ARTIFACT,
                new io.github.finbridge.spi.PluginContext(java.util.Map.of()));
        runtime.activate(previous.key());
        UUID tenant = UUID.randomUUID();
        PluginBinding newBinding = new PluginBinding(tenant, CAPABILITY, candidate.key(), 2);
        when(controlPlane.findArtifact(candidate.key())).thenReturn(Optional.of(artifact(candidate, "LOADED", false)));
        when(controlPlane.updateBindingAndRetirePrevious(tenant, CAPABILITY, candidate.key(), 1, "operator"))
                .thenReturn(new PluginControlPlaneRepository.BindingSwitch(newBinding, previous.key()));
        when(controlPlane.bindings(previous.key())).thenReturn(List.of());
        when(controlPlane.bindings(candidate.key())).thenReturn(List.of(newBinding));
        when(lending.hasUnresolvedPaymentOwner(previous.key())).thenReturn(false);

        lifecycle.upgrade(tenant, CAPABILITY, 1, ARTIFACT, Duration.ZERO);

        assertEquals(PluginLifecycleState.UNLOADED, previous.state());
        verify(controlPlane).setDesiredState(previous.key(), "RETIRED", "operator", "PLUGIN_UNLOADED");
    }

    @Test
    void retiredProviderWithActiveInvocationIsNotUnloadedDuringUpgrade() throws Exception {
        PluginHandle candidate = installedHandle();
        PluginHandle previous = runtime.install(SECOND_RISK_ARTIFACT,
                new io.github.finbridge.spi.PluginContext(java.util.Map.of()));
        runtime.activate(previous.key());
        try (var lease = previous.acquire(io.github.finbridge.runtime.InvocationKind.BUSINESS)) {
            assertSame(previous, lease.handle());
            assertEquals(1, previous.activeInvocations());
            UUID tenant = UUID.randomUUID();
            PluginBinding newBinding = new PluginBinding(tenant, CAPABILITY, candidate.key(), 2);
            when(controlPlane.findArtifact(candidate.key()))
                    .thenReturn(Optional.of(artifact(candidate, "LOADED", false)));
            when(controlPlane.updateBindingAndRetirePrevious(tenant, CAPABILITY, candidate.key(), 1, "operator"))
                    .thenReturn(new PluginControlPlaneRepository.BindingSwitch(newBinding, previous.key()));
            when(controlPlane.bindings(previous.key())).thenReturn(List.of());
            when(controlPlane.bindings(candidate.key())).thenReturn(List.of(newBinding));
            when(lending.hasUnresolvedPaymentOwner(previous.key())).thenReturn(false);

            lifecycle.upgrade(tenant, CAPABILITY, 1, ARTIFACT, Duration.ZERO);
            assertEquals(PluginLifecycleState.DRAINING, previous.state());
            verify(controlPlane).recordEvent(previous.key(), "PLUGIN_DRAIN_BLOCKED", "operator",
                    "PLUGIN_INVOCATIONS_ACTIVE");
        }
    }

    @Test
    void retiredDrainingProviderWithReconciliationLeaseRemainsLoaded() throws Exception {
        PluginHandle candidate = installedHandle();
        PluginHandle previous = runtime.install(SECOND_RISK_ARTIFACT,
                new io.github.finbridge.spi.PluginContext(java.util.Map.of()));
        previous.activate();
        previous.beginDrain();
        try (var lease = previous.acquire(io.github.finbridge.runtime.InvocationKind.RECONCILIATION)) {
            assertSame(previous, lease.handle());
            UUID tenant = UUID.randomUUID();
            PluginBinding newBinding = new PluginBinding(tenant, CAPABILITY, candidate.key(), 2);
            when(controlPlane.findArtifact(candidate.key()))
                    .thenReturn(Optional.of(artifact(candidate, "LOADED", false)));
            when(controlPlane.updateBindingAndRetirePrevious(tenant, CAPABILITY, candidate.key(), 1, "operator"))
                    .thenReturn(new PluginControlPlaneRepository.BindingSwitch(newBinding, previous.key()));
            when(controlPlane.bindings(previous.key())).thenReturn(List.of());
            when(controlPlane.bindings(candidate.key())).thenReturn(List.of(newBinding));
            when(lending.hasUnresolvedPaymentOwner(previous.key())).thenReturn(false);

            lifecycle.upgrade(tenant, CAPABILITY, 1, ARTIFACT, Duration.ZERO);

            assertEquals(PluginLifecycleState.DRAINING, previous.state());
            verify(controlPlane).recordEvent(previous.key(), "PLUGIN_DRAIN_BLOCKED", "operator",
                    "PLUGIN_INVOCATIONS_ACTIVE");
        }
    }

    @Test
    void retiredProviderThatRemainsBoundIsNotDrained() throws Exception {
        PluginHandle candidate = installedHandle();
        PluginHandle previous = runtime.install(SECOND_RISK_ARTIFACT,
                new io.github.finbridge.spi.PluginContext(java.util.Map.of()));
        previous.activate();
        UUID tenant = UUID.randomUUID();
        PluginBinding newBinding = new PluginBinding(tenant, CAPABILITY, candidate.key(), 2);
        PluginBinding retainedBinding = new PluginBinding(tenant, CAPABILITY, previous.key(), 1);
        when(controlPlane.findArtifact(candidate.key()))
                .thenReturn(Optional.of(artifact(candidate, "LOADED", false)));
        when(controlPlane.updateBindingAndRetirePrevious(tenant, CAPABILITY, candidate.key(), 1, "operator"))
                .thenReturn(new PluginControlPlaneRepository.BindingSwitch(newBinding, previous.key()));
        when(controlPlane.bindings(previous.key())).thenReturn(List.of(), List.of(retainedBinding));
        when(controlPlane.bindings(candidate.key())).thenReturn(List.of(newBinding));

        lifecycle.upgrade(tenant, CAPABILITY, 1, ARTIFACT, Duration.ZERO);

        assertEquals(PluginLifecycleState.ACTIVE, previous.state());
        verify(controlPlane).recordEvent(previous.key(), "PLUGIN_DRAIN_BLOCKED", "operator",
                "PLUGIN_STILL_BOUND");
    }

    @Test
    void retiredLoadedProviderIsNotActivatedForDrain() throws Exception {
        PluginHandle candidate = installedHandle();
        PluginHandle previous = runtime.install(SECOND_RISK_ARTIFACT,
                new io.github.finbridge.spi.PluginContext(java.util.Map.of()));
        UUID tenant = UUID.randomUUID();
        PluginBinding newBinding = new PluginBinding(tenant, CAPABILITY, candidate.key(), 2);
        when(controlPlane.findArtifact(candidate.key()))
                .thenReturn(Optional.of(artifact(candidate, "LOADED", false)));
        when(controlPlane.updateBindingAndRetirePrevious(tenant, CAPABILITY, candidate.key(), 1, "operator"))
                .thenReturn(new PluginControlPlaneRepository.BindingSwitch(newBinding, previous.key()));
        when(controlPlane.bindings(previous.key())).thenReturn(List.of());
        when(controlPlane.bindings(candidate.key())).thenReturn(List.of(newBinding));

        lifecycle.upgrade(tenant, CAPABILITY, 1, ARTIFACT, Duration.ZERO);

        assertEquals(PluginLifecycleState.LOADED, previous.state());
        verify(controlPlane, never()).recordEvent(eq(previous.key()), eq("PLUGIN_DRAIN_BLOCKED"),
                eq("operator"), any());
    }

    @Test
    void restoreAtStartupActivatesVerifiedArtifactThenRestoresDurableBinding() {
        PluginHandle handle = runtime.install(ARTIFACT, new io.github.finbridge.spi.PluginContext(java.util.Map.of()));
        UUID tenant = UUID.randomUUID();
        when(controlPlane.restorableArtifacts()).thenReturn(List.of(artifact(handle, "ACTIVE", false)));
        when(controlPlane.bindings()).thenReturn(List.of(new PluginBinding(tenant, CAPABILITY, handle.key(), 4)));

        lifecycle.restoreAtStartup();

        assertEquals(PluginLifecycleState.ACTIVE, handle.state());
        assertEquals(4, runtime.binding(tenant, CAPABILITY).version());
    }

    @Test
    void startupRestoreAuditsMissingArtifactsAndSkipsUnretainedRetiredArtifacts() {
        PluginKey missing = new PluginKey("missing-risk", "1.0.0");
        var missingArtifact = new PluginControlPlaneRepository.RegisteredArtifact("missing.jar", missing,
                "a".repeat(64), PluginType.RISK_DATA, Set.of(CAPABILITY), "ACTIVE", false);
        when(controlPlane.restorableArtifacts()).thenReturn(List.of(missingArtifact));
        when(controlPlane.bindings()).thenReturn(List.of());

        lifecycle.restoreAtStartup();

        verify(controlPlane).recordEvent(missing, "PLUGIN_RESTORE_FAILED", "SYSTEM_STARTUP", "ARTIFACT_MISSING");
        assertTrue(runtime.plugins().isEmpty());
    }

    @Test
    void startupRestoreSkipsRetiredArtifactWithoutUnresolvedOwner() {
        PluginKey retiredKey = new PluginKey("retired-risk", "1.0.0");
        var retired = new PluginControlPlaneRepository.RegisteredArtifact("retired.jar", retiredKey,
                "a".repeat(64), PluginType.RISK_DATA, Set.of(CAPABILITY), "RETIRED", false);
        when(controlPlane.restorableArtifacts()).thenReturn(List.of(retired));
        when(controlPlane.bindings()).thenReturn(List.of());

        lifecycle.restoreAtStartup();

        assertTrue(runtime.plugins().isEmpty());
        verify(controlPlane, never()).recordEvent(eq(retiredKey), eq("PLUGIN_RESTORE_FAILED"),
                eq("SYSTEM_STARTUP"), any());
    }

    @Test
    void startupRestoreReportsBindingTargetThatIsNotActiveOrCapable() {
        PluginHandle handle = runtime.install(ARTIFACT,
                new io.github.finbridge.spi.PluginContext(java.util.Map.of()));
        UUID tenant = UUID.randomUUID();
        when(controlPlane.restorableArtifacts()).thenReturn(List.of(artifact(handle, "ACTIVE", false)));
        when(controlPlane.bindings()).thenReturn(List.of(new PluginBinding(tenant, Capability.DISBURSE,
                handle.key(), 1)));

        lifecycle.restoreAtStartup();

        verify(controlPlane).recordEvent(handle.key(), "BINDING_RESTORE_FAILED", "SYSTEM_STARTUP",
                "BINDING_RESTORE_FAILED");
    }

    @Test
    void startupRestoreRecordsBindingAlreadyRestoredAsFailure() throws Exception {
        PluginHandle handle = runtime.install(ARTIFACT,
                new io.github.finbridge.spi.PluginContext(java.util.Map.of()));
        UUID tenant = UUID.randomUUID();
        PluginBinding binding = new PluginBinding(tenant, CAPABILITY, handle.key(), 1);
        runtime.activate(handle.key());
        runtime.restoreBinding(tenant, CAPABILITY, handle.key(), 1);
        when(controlPlane.restorableArtifacts()).thenReturn(List.of(artifact(handle, "ACTIVE", false)));
        when(controlPlane.bindings()).thenReturn(List.of(binding));

        lifecycle.restoreAtStartup();

        verify(controlPlane).recordEvent(handle.key(), "BINDING_RESTORE_FAILED", "SYSTEM_STARTUP",
                "BINDING_RESTORE_FAILED");
    }

    @Test
    void inactiveStoredBindingIsRejectedAndAudited() throws Exception {
        PluginHandle handle = runtime.install(ARTIFACT,
                new io.github.finbridge.spi.PluginContext(java.util.Map.of()));
        UUID tenant = UUID.randomUUID();
        when(controlPlane.bindingSwitch(tenant, CAPABILITY, handle.key(), 0, "operator"))
                .thenReturn(new PluginControlPlaneRepository.BindingSwitch(
                        new PluginBinding(tenant, CAPABILITY, handle.key(), 1), handle.key()));

        assertThrows(IllegalStateException.class, () -> lifecycle.bind(tenant, CAPABILITY, handle.key(), 0));
        verify(controlPlane, never()).bindingSwitch(tenant, CAPABILITY, handle.key(), 0, "operator");
    }

    @Test
    void startupRestoreRetainsRetiredOwnerInDrainStateForPaymentReconciliation() throws Exception {
        PluginHandle handle = runtime.install(ARTIFACT,
                new io.github.finbridge.spi.PluginContext(java.util.Map.of()));
        when(controlPlane.restorableArtifacts()).thenReturn(List.of(artifact(handle, "RETIRED", true)));
        when(controlPlane.bindings()).thenReturn(List.of());

        lifecycle.restoreAtStartup();

        assertEquals(PluginLifecycleState.DRAINING, handle.state());
        verify(controlPlane).recordEvent(handle.key(), "PLUGIN_RESTORED", "SYSTEM_STARTUP",
                "RETIRED_OWNER_ARTIFACT_RESTORED_FOR_RECONCILIATION");
    }

    @Test
    void startupRestoreDiscardsArtifactWithMismatchedChecksum() throws Exception {
        PluginHandle handle = runtime.install(ARTIFACT,
                new io.github.finbridge.spi.PluginContext(java.util.Map.of()));
        var mismatch = new PluginControlPlaneRepository.RegisteredArtifact(ARTIFACT, handle.key(),
                "0".repeat(64), PluginType.RISK_DATA, Set.of(CAPABILITY), "ACTIVE", false);
        when(controlPlane.restorableArtifacts()).thenReturn(List.of(mismatch));
        when(controlPlane.bindings()).thenReturn(List.of());

        lifecycle.restoreAtStartup();

        assertTrue(runtime.plugins().isEmpty());
        verify(controlPlane).recordEvent(handle.key(), "PLUGIN_RESTORE_FAILED", "SYSTEM_STARTUP",
                "ARTIFACT_IDENTITY_MISMATCH");
    }

    @Test
    void lifecycleReadOperationsRequireAdministratorRole() {
        PluginKey key = new PluginKey("secured", "1.0.0");
        SecurityContextHolder.clearContext();

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> lifecycle.events(key));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> lifecycle.bindings(UUID.randomUUID()));
        verify(controlPlane, never()).lifecycleEvents(any());
    }

    @Test
    void drainAndUnloadRejectBindingsAndPreserveProviderWhenOwnerWorkIsRetained() throws Exception {
        PluginHandle handle = installedActiveHandle();
        doThrow(new IllegalStateException("Plugin version is still bound"))
                .when(controlPlane).ensureNoBindings(handle.key());
        assertThrows(IllegalStateException.class, () -> lifecycle.drain(handle.key(), Duration.ZERO));
        assertThrows(IllegalStateException.class, () -> lifecycle.unload(handle.key(), Duration.ZERO));
        assertEquals(PluginLifecycleState.ACTIVE, handle.state());

        doNothing().when(controlPlane).ensureNoBindings(handle.key());
        when(controlPlane.bindings(handle.key())).thenReturn(List.of());
        when(lending.hasUnresolvedPaymentOwner(handle.key())).thenReturn(true);
        assertFalse(lifecycle.drain(handle.key(), Duration.ZERO));
        assertThrows(IllegalStateException.class, () -> lifecycle.unload(handle.key(), Duration.ZERO));
        assertEquals(PluginLifecycleState.DRAINING, handle.state());
        assertTrue(runtime.plugins().contains(handle));
    }

    @Test
    void administratorCanReadDurableLifecycleEventsAndBindings() {
        PluginKey key = new PluginKey("audited-risk", "1.0.0");
        UUID tenant = UUID.randomUUID();
        var event = new PluginControlPlaneRepository.PluginLifecycleAuditEvent(UUID.randomUUID(), key,
                "PLUGIN_LOADED", "operator", java.time.Instant.parse("2026-03-01T10:00:00Z"), "LOADED");
        when(controlPlane.lifecycleEvents(key)).thenReturn(List.of(event));
        when(controlPlane.bindings(tenant)).thenReturn(List.of());

        assertEquals(List.of(event), lifecycle.events(key));
        assertEquals(List.of(), lifecycle.bindings(tenant));
        verify(controlPlane).lifecycleEvents(key);
        verify(controlPlane).bindings(tenant);
        HostAuthorization.requireAdministrator();
    }

    private PluginHandle installedHandle() {
        when(controlPlane.registeredArtifactName(ARTIFACT)).thenReturn(Optional.empty());
        return lifecycle.install(ARTIFACT);
    }

    private PluginHandle installedActiveHandle() {
        PluginHandle handle = installedHandle();
        when(controlPlane.findArtifact(handle.key())).thenReturn(Optional.of(artifact(handle, "ACTIVE", false)));
        lifecycle.activate(handle.key());
        return handle;
    }

    private PluginControlPlaneRepository.RegisteredArtifact artifact(PluginHandle handle, String state,
            boolean retained) {
        return new PluginControlPlaneRepository.RegisteredArtifact(ARTIFACT, handle.key(), handle.sha256(),
                handle.descriptor().pluginType(), handle.descriptor().capabilities(), state, retained);
    }

    private static final class ProviderFreeParent extends ClassLoader {
        private static final String PROVIDER_PACKAGE = "io.github.finbridge.plugins.";
        private static final String RISK_SERVICE =
                "META-INF/services/io.github.finbridge.spi.risk.RiskDataProvider";

        private ProviderFreeParent(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(PROVIDER_PACKAGE)) {
                throw new ClassNotFoundException("Synthetic provider classes must come from the artifact");
            }
            return super.loadClass(name, resolve);
        }

        @Override
        public java.net.URL getResource(String name) {
            return RISK_SERVICE.equals(name) ? null : super.getResource(name);
        }

        @Override
        public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
            return RISK_SERVICE.equals(name) ? java.util.Collections.emptyEnumeration() : super.getResources(name);
        }
    }
}
