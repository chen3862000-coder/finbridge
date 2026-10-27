package io.github.finbridge.runtime;

import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.FinBridgePlugin;
import io.github.finbridge.spi.PluginContext;
import io.github.finbridge.spi.PluginDescriptor;
import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.spi.risk.RiskDataProvider;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.UUID;

public final class PluginRuntime {
    private final PluginArtifactRepository artifacts;
    private final PluginDiscovery discovery;
    private final PluginRegistry registry = new PluginRegistry();
    private final PluginBindingRegistry bindingRegistry = new PluginBindingRegistry();
    private final int supportedSpiMajor;

    public PluginRuntime(Path artifactRoot, ClassLoader parent, int supportedSpiMajor) {
        this.artifacts = new PluginArtifactRepository(artifactRoot);
        this.discovery = new PluginDiscovery(parent);
        if (supportedSpiMajor < 1) {
            throw new IllegalArgumentException("supportedSpiMajor must be positive");
        }
        this.supportedSpiMajor = supportedSpiMajor;
    }

    public PluginHandle install(String artifactName, PluginContext context) {
        Objects.requireNonNull(context, "context");
        Path artifact = artifacts.resolve(Objects.requireNonNull(artifactName, "artifactName"));
        artifacts.validate(artifact);
        String checksum = artifacts.checksum(artifact);
        PluginClassLoader loader = discovery.createLoader(artifact);
        FinBridgePlugin provider = null;
        try {
            provider = discovery.discover(loader);
            PluginDescriptor descriptor = Objects.requireNonNull(provider.descriptor(),
                    "Plugin descriptor is required");
            validatePlugin(provider, descriptor);
            initializePlugin(provider, loader, context);
            return registerPlugin(descriptor, checksum, provider, loader);
        } catch (PluginLoadException failure) {
            discovery.closeFailedLoad(provider, loader);
            throw failure;
        } catch (ServiceConfigurationError | LinkageError | RuntimeException failure) {
            discovery.closeFailedLoad(provider, loader);
            throw new PluginLoadException("Plugin artifact failed validation: " + artifactName,
                    failure);
        }
    }

    public List<PluginHandle> plugins() {
        return registry.plugins();
    }

    public PluginHandle plugin(PluginKey key) {
        return registry.plugin(Objects.requireNonNull(key, "key"));
    }

    public void activate(PluginKey key) {
        plugin(key).activate();
    }

    public void resumeFromDrain(PluginKey key) {
        plugin(key).resumeFromDrain();
    }

    public void discardLoaded(PluginKey key) throws IOException {
        PluginHandle handle = plugin(key);
        handle.discardLoaded();
        if (!registry.remove(key, handle)) {
            throw new IllegalStateException("Plugin registry changed while discarding loaded plugin");
        }
    }

    public PluginHandle installRiskProvider(String artifactName, PluginContext context,
                                            io.github.finbridge.spi.risk.RiskDataType expectedType) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(expectedType, "expectedType");
        Path artifact = artifacts.resolve(Objects.requireNonNull(artifactName, "artifactName"));
        artifacts.validate(artifact);
        String checksum = artifacts.checksum(artifact);
        PluginClassLoader loader = discovery.createLoader(artifact);
        RiskDataProvider provider = null;
        try {
            provider = discovery.discoverRiskProvider(loader);
            PluginDescriptor descriptor = Objects.requireNonNull(provider.descriptor(),
                    "Plugin descriptor is required");
            discovery.validateDescriptor(descriptor, supportedSpiMajor);
            discovery.validateProviderContract(provider, descriptor);
            if (provider.supportedType() != expectedType) {
                throw new PluginLoadException("Risk provider does not match requested data capability");
            }
            RiskDataProvider initialized = provider;
            PluginDiscovery.withContextLoader(loader, () -> {
                initialized.initialize(context);
                if (!initialized.healthCheck().healthy()) {
                    throw new PluginLoadException("Risk provider health check failed");
                }
                return null;
            });
            return registerPlugin(descriptor, checksum, provider, loader);
        } catch (PluginLoadException failure) {
            discovery.closeFailedLoad(provider, loader);
            throw failure;
        } catch (ServiceConfigurationError | LinkageError | RuntimeException failure) {
            discovery.closeFailedLoad(provider, loader);
            throw new PluginLoadException("Risk plugin artifact failed validation: " + artifactName,
                    failure);
        }
    }

    public PluginBinding binding(UUID tenantId, Capability capability) {
        return bindingRegistry.binding(tenantId, capability);
    }

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

    public PluginBinding publishBinding(PluginBinding binding) {
        Objects.requireNonNull(binding, "binding");
        PluginHandle target = plugin(binding.pluginKey());
        if (target.state() != PluginLifecycleState.ACTIVE
                || !target.descriptor().capabilities().contains(binding.capability())) {
            throw new IllegalStateException("Persisted binding target is not active and capable");
        }
        return bindingRegistry.publish(binding);
    }

    public long unbind(UUID tenantId, Capability capability, long expectedVersion) {
        return bindingRegistry.unbind(tenantId, capability, expectedVersion);
    }

    public long publishUnbind(UUID tenantId, Capability capability, long nextVersion) {
        return bindingRegistry.publishUnbind(tenantId, capability, nextVersion);
    }

    public PluginInvocationLease route(UUID tenantId, Capability capability,
                                       InvocationKind invocationKind) {
        PluginBinding selected = binding(tenantId, capability);
        return plugin(selected.pluginKey()).acquire(invocationKind);
    }

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

    private void initializePlugin(FinBridgePlugin provider, PluginClassLoader loader,
                                  PluginContext context) {
        PluginDiscovery.withContextLoader(loader, () -> {
            provider.initialize(context);
            if (!provider.healthCheck().healthy()) {
                throw new PluginLoadException("Plugin health check failed");
            }
            return null;
        });
    }

    private void validatePlugin(FinBridgePlugin provider, PluginDescriptor descriptor) {
        discovery.validateDescriptor(descriptor, supportedSpiMajor);
        discovery.validateProviderContract(provider, descriptor);
    }

    private PluginHandle registerPlugin(PluginDescriptor descriptor, String checksum,
                                        FinBridgePlugin provider, PluginClassLoader loader) {
        PluginHandle handle = new PluginHandle(descriptor, checksum, provider, loader);
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
        discovery.closeFailedLoad(provider, loader);
        return existing;
    }
}
