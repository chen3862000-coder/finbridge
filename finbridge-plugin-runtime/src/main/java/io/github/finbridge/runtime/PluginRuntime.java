package io.github.finbridge.runtime;

import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.FinBridgePlugin;
import io.github.finbridge.spi.PluginContext;
import io.github.finbridge.spi.PluginDescriptor;
import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.spi.PluginHealth;
import io.github.finbridge.spi.risk.RiskDataProvider;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.UUID;

/** Loads external provider artifacts and coordinates registry, tenant routing, and lifecycle state. */
public final class PluginRuntime implements AutoCloseable {
    private final PluginArtifactRepository artifacts;
    private final PluginDiscovery discovery;
    private final PluginLifecycleCallbackExecutor lifecycleCallbacks;
    private final PluginRegistry registry = new PluginRegistry();
    private final PluginBindingRegistry bindingRegistry = new PluginBindingRegistry();
    private final int supportedSpiMajor;

    public PluginRuntime(Path artifactRoot, ClassLoader parent, int supportedSpiMajor) {
        this(artifactRoot, parent, supportedSpiMajor, java.time.Duration.ofSeconds(2));
    }

    public PluginRuntime(Path artifactRoot, ClassLoader parent, int supportedSpiMajor,
                         java.time.Duration lifecycleCallbackTimeout) {
        this.artifacts = new PluginArtifactRepository(artifactRoot);
        this.lifecycleCallbacks = new PluginLifecycleCallbackExecutor(lifecycleCallbackTimeout);
        this.discovery = new PluginDiscovery(Objects.requireNonNull(parent, "parent"), lifecycleCallbacks);
        if (supportedSpiMajor < 1) {
            lifecycleCallbacks.close();
            throw new IllegalArgumentException("supportedSpiMajor must be positive");
        }
        this.supportedSpiMajor = supportedSpiMajor;
    }

    /** Validates and loads one allowlisted artifact without activating it. */
    public PluginHandle install(String artifactName, PluginContext context) {
        Objects.requireNonNull(context, "context");
        Path artifact = artifacts.resolve(Objects.requireNonNull(artifactName, "artifactName"));
        artifacts.validate(artifact);
        String checksum = artifacts.checksum(artifact);
        PluginClassLoader loader = discovery.createLoader(artifact);
        FinBridgePlugin provider = null;
        PluginDescriptor descriptor = null;
        try {
            provider = discovery.discover(loader);
            descriptor = Objects.requireNonNull(provider.descriptor(),
                    "Plugin descriptor is required");
            validatePlugin(provider, descriptor);
            initializePlugin(provider, loader, context);
            return registerPlugin(descriptor, checksum, provider, loader);
        } catch (PluginLoadException failure) {
            closeFailedLoad(provider, descriptor, checksum, loader, failure);
            throw failure;
        } catch (ServiceConfigurationError | LinkageError | RuntimeException failure) {
            closeFailedLoad(provider, descriptor, checksum, loader, failure);
            throw new PluginLoadException("Plugin artifact failed validation: " + artifactName,
                    failure);
        }
    }

    /** Returns a snapshot of loaded plugin handles. */
    public List<PluginHandle> plugins() {
        return registry.plugins();
    }

    /** Returns the loaded handle for a stable plugin identity. */
    public PluginHandle plugin(PluginKey key) {
        return registry.plugin(Objects.requireNonNull(key, "key"));
    }

    /** Makes a successfully loaded provider available for new bindings and invocations. */
    public void activate(PluginKey key) {
        plugin(key).activate();
    }

    /** Resumes a draining provider when a lifecycle operation is safely rolled back. */
    public void resumeFromDrain(PluginKey key) {
        plugin(key).resumeFromDrain();
    }

    /** Discards a loaded but not active provider and removes it from the registry. */
    public void discardLoaded(PluginKey key) throws IOException {
        PluginHandle handle = plugin(key);
        handle.discardLoaded();
        if (!registry.remove(key, handle)) {
            throw new IllegalStateException("Plugin registry changed while discarding loaded plugin");
        }
    }

    /** Loads a risk provider only when its advertised data type matches the expected capability. */
    public PluginHandle installRiskProvider(String artifactName, PluginContext context,
                                            io.github.finbridge.spi.risk.RiskDataType expectedType) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(expectedType, "expectedType");
        Path artifact = artifacts.resolve(Objects.requireNonNull(artifactName, "artifactName"));
        artifacts.validate(artifact);
        String checksum = artifacts.checksum(artifact);
        PluginClassLoader loader = discovery.createLoader(artifact);
        RiskDataProvider provider = null;
        PluginDescriptor descriptor = null;
        try {
            provider = discovery.discoverRiskProvider(loader);
            descriptor = Objects.requireNonNull(provider.descriptor(),
                    "Plugin descriptor is required");
            discovery.validateDescriptor(descriptor, supportedSpiMajor);
            discovery.validateProviderContract(provider, descriptor);
            if (provider.supportedType() != expectedType) {
                throw new PluginLoadException("Risk provider does not match requested data capability");
            }
            RiskDataProvider initialized = provider;
            PluginDiscovery.withContextLoader(loader, () -> {
                lifecycleCallbacks.execute("initialize", loader, () -> initialized.initialize(context));
                PluginHealth health = healthCheck(initialized, loader);
                if (!health.healthy()) {
                    throw new PluginLoadException("Risk provider health check failed");
                }
                return null;
            });
            return registerPlugin(descriptor, checksum, provider, loader);
        } catch (PluginLoadException failure) {
            closeFailedLoad(provider, descriptor, checksum, loader, failure);
            throw failure;
        } catch (ServiceConfigurationError | LinkageError | RuntimeException failure) {
            closeFailedLoad(provider, descriptor, checksum, loader, failure);
            throw new PluginLoadException("Risk plugin artifact failed validation: " + artifactName,
                    failure);
        }
    }

    /** Returns the current tenant binding for a capability. */
    public PluginBinding binding(UUID tenantId, Capability capability) {
        return bindingRegistry.binding(tenantId, capability);
    }

    /** Publishes an optimistic binding to an active provider supporting the capability. */
    public PluginBinding bind(UUID tenantId, Capability capability, PluginKey pluginKey,
                              long expectedVersion) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(capability, "capability");
        PluginHandle target = plugin(pluginKey);
        if (target.state() != PluginLifecycleState.ACTIVE) {
            throw new IllegalStateException("Only active plugins can be bound");
        }
        if (!target.descriptor().capabilities().contains(capability)) {
            throw new IllegalArgumentException("Plugin does not support capability " + capability);
        }
        return bindingRegistry.bind(tenantId, capability, target, pluginKey, expectedVersion);
    }

    /** Restores a persisted binding after its provider has been verified and activated. */
    public PluginBinding restoreBinding(UUID tenantId, Capability capability, PluginKey pluginKey,
            long persistedVersion) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(capability, "capability");
        PluginHandle target = plugin(Objects.requireNonNull(pluginKey, "pluginKey"));
        if (target.state() != PluginLifecycleState.ACTIVE) {
            throw new IllegalStateException("Only active plugins can be restored as bindings");
        }
        if (!target.descriptor().capabilities().contains(capability)) {
            throw new IllegalArgumentException("Plugin does not support capability " + capability);
        }
        return bindingRegistry.restore(tenantId, capability, pluginKey, persistedVersion);
    }

    /** Publishes a binding already committed by the durable control plane. */
    public PluginBinding publishBinding(PluginBinding binding) {
        Objects.requireNonNull(binding, "binding");
        PluginHandle target = plugin(binding.pluginKey());
        if (target.state() != PluginLifecycleState.ACTIVE
                || !target.descriptor().capabilities().contains(binding.capability())) {
            throw new IllegalStateException("Persisted binding target is not active and capable");
        }
        return bindingRegistry.publish(binding);
    }

    /** Removes a tenant binding only if its current revision matches the expected value. */
    public long unbind(UUID tenantId, Capability capability, long expectedVersion) {
        return bindingRegistry.unbind(tenantId, capability, expectedVersion);
    }

    /** Applies a previously persisted unbind revision to in-memory routing. */
    public long publishUnbind(UUID tenantId, Capability capability, long nextVersion) {
        return bindingRegistry.publishUnbind(tenantId, capability, nextVersion);
    }

    /** Acquires an invocation lease for the provider currently routed to this tenant/capability. */
    public PluginInvocationLease route(UUID tenantId, Capability capability,
                                       InvocationKind invocationKind) {
        PluginBinding selected = binding(tenantId, capability);
        return plugin(selected.pluginKey()).acquire(invocationKind);
    }

    /**
     * Stops new business routing and waits for active invocations, retaining bound or owner-pinned
     * providers. Reconciliation may still acquire a lease while the provider drains.
     */
    public boolean drain(PluginKey key, java.time.Duration timeout,
                         PluginRetentionPolicy retentionPolicy) throws InterruptedException {
        PluginHandle handle = plugin(key);
        if (handle.state() == PluginLifecycleState.ACTIVE) {
            handle.beginDrain();
        }
        if (handle.state() != PluginLifecycleState.DRAINING) {
            throw new IllegalStateException("Plugin is not drainable: " + handle.state());
        }
        if (bindingRegistry.isBound(key) || !retentionPolicy.canUnload(key)) {
            return false;
        }
        return handle.awaitDrained(timeout);
    }

    /** Unloads only after bindings, retained payment ownership, and invocation leases are clear. */
    public void unload(PluginKey key, java.time.Duration timeout,
                       PluginRetentionPolicy retentionPolicy) throws IOException, InterruptedException {
        PluginHandle handle = plugin(key);
        if (handle.state() == PluginLifecycleState.ACTIVE) {
            handle.beginDrain();
        }
        if (bindingRegistry.isBound(key) || !retentionPolicy.canUnload(key)) {
            throw new IllegalStateException("Plugin is still referenced by bindings or retained work");
        }
        if (!handle.awaitDrained(timeout)) {
            throw new IllegalStateException("Timed out waiting for plugin invocations to drain");
        }
        handle.unload();
        if (!registry.remove(key, handle)) {
            throw new IllegalStateException("Plugin registry changed during unload");
        }
    }

    @Override
    public void close() {
        lifecycleCallbacks.close();
    }

    private void initializePlugin(FinBridgePlugin provider, PluginClassLoader loader,
                                  PluginContext context) {
        PluginDiscovery.withContextLoader(loader, () -> {
            lifecycleCallbacks.execute("initialize", loader, () -> provider.initialize(context));
            if (!healthCheck(provider, loader).healthy()) {
                throw new PluginLoadException("Plugin health check failed");
            }
            return null;
        });
    }

    private PluginHealth healthCheck(FinBridgePlugin provider, PluginClassLoader loader) {
        java.util.concurrent.atomic.AtomicReference<PluginHealth> health =
                new java.util.concurrent.atomic.AtomicReference<>();
        lifecycleCallbacks.execute("health check", loader, () -> health.set(provider.healthCheck()));
        return Objects.requireNonNull(health.get(), "Plugin health status is required");
    }

    private void validatePlugin(FinBridgePlugin provider, PluginDescriptor descriptor) {
        discovery.validateDescriptor(descriptor, supportedSpiMajor);
        discovery.validateProviderContract(provider, descriptor);
    }

    private PluginHandle registerPlugin(PluginDescriptor descriptor, String checksum,
                                        FinBridgePlugin provider, PluginClassLoader loader) {
        PluginHandle handle = new PluginHandle(descriptor, checksum, provider, loader, lifecycleCallbacks);
        PluginHandle existing = registry.putIfAbsent(handle);
        return reuseOrReject(existing, checksum, provider, loader, handle);
    }

    private PluginHandle reuseOrReject(PluginHandle existing, String checksum,
                                       FinBridgePlugin provider, PluginClassLoader loader,
                                       PluginHandle candidate) {
        if (existing == null) {
            return candidate;
        }
        boolean sameBytes = existing.sha256().equals(checksum);
        if (!sameBytes) {
            throw new PluginLoadException("Plugin identity already exists with different bytes");
        }
        discovery.closeFailedLoad(provider, loader, false);
        return existing;
    }

    private static boolean callbackTimedOut(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof PluginLifecycleCallbackException callbackFailure
                    && callbackFailure.timedOut()) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private void closeFailedLoad(FinBridgePlugin provider, PluginDescriptor descriptor, String checksum,
                                 PluginClassLoader loader, Throwable failure) {
        boolean quarantined = discovery.closeFailedLoad(provider, loader, callbackTimedOut(failure));
        if (quarantined && provider != null && descriptor != null) {
            registerQuarantinedHandle(provider, descriptor, checksum, loader, failure);
        }
    }

    private void registerQuarantinedHandle(FinBridgePlugin provider, PluginDescriptor descriptor,
                                           String checksum, PluginClassLoader loader, Throwable failure) {
        PluginHandle quarantined = new PluginHandle(descriptor, checksum, provider, loader,
                lifecycleCallbacks);
        quarantined.markFailed("PLUGIN_LIFECYCLE_CALLBACK_TIMED_OUT");
        PluginHandle existing = registry.putIfAbsent(quarantined);
        if (existing != null) {
            discardQuarantinedLoader(loader);
            throw new PluginLoadException("Timed-out provider could not be quarantined under its identity",
                    failure);
        }
    }

    void discardQuarantinedLoader(URLClassLoader loader) {
        if (!lifecycleCallbacks.canCloseLoader(loader)) {
            return;
        }
        lifecycleCallbacks.releaseQuarantine(loader);
        try {
            loader.close();
        } catch (IOException ignored) {
            // Preserve the identity collision that prevented a quarantined registration.
        }
    }
}
