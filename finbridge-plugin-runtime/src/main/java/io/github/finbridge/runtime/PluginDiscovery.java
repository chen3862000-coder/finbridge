package io.github.finbridge.runtime;

import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.FinBridgePlugin;
import io.github.finbridge.spi.PluginDescriptor;
import io.github.finbridge.spi.PluginType;
import io.github.finbridge.spi.payment.PaymentProvider;
import io.github.finbridge.spi.risk.RiskDataProvider;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

final class PluginDiscovery {
    private final ClassLoader parent;
    private final PluginLifecycleCallbackExecutor lifecycleCallbacks;

    @FunctionalInterface
    interface PluginCall<T> {
        T call();
    }

    PluginDiscovery(ClassLoader parent, PluginLifecycleCallbackExecutor lifecycleCallbacks) {
        this.parent = parent;
        this.lifecycleCallbacks = lifecycleCallbacks;
    }

    PluginClassLoader createLoader(Path artifact) {
        try {
            if (!Files.isRegularFile(artifact) || !Files.isReadable(artifact)) {
                throw new IOException("Plugin artifact is not a readable regular file");
            }
            URL url = artifact.toUri().toURL();
            return new PluginClassLoader(url, parent);
        } catch (IOException failure) {
            throw new PluginLoadException("Could not create plugin class loader", failure);
        }
    }

    Capability capabilityFor(io.github.finbridge.spi.risk.RiskDataType dataType) {
        return switch (dataType) {
            case CREDIT_SCORE -> Capability.CREDIT_SCORE;
            case BLACKLIST -> Capability.BLACKLIST;
            case FRAUD_SIGNAL -> Capability.FRAUD_SIGNAL;
        };
    }

    FinBridgePlugin discover(PluginClassLoader loader) {
        List<FinBridgePlugin> found = new ArrayList<>();
        withContextLoader(loader, () -> {
            ServiceLoader.load(PaymentProvider.class, loader).forEach(found::add);
            ServiceLoader.load(RiskDataProvider.class, loader).forEach(found::add);
            return null;
        });
        if (found.size() != 1) {
            throw new PluginLoadException("Plugin JAR must expose exactly one supported provider");
        }
        return found.getFirst();
    }

    RiskDataProvider discoverRiskProvider(PluginClassLoader loader) {
        List<RiskDataProvider> providers = new ArrayList<>();
        withContextLoader(loader, () -> {
            ServiceLoader.load(RiskDataProvider.class, loader).forEach(providers::add);
            return null;
        });
        if (providers.size() != 1) {
            throw new PluginLoadException("Risk plugin JAR must expose exactly one risk provider");
        }
        return providers.getFirst();
    }

    void validateDescriptor(PluginDescriptor descriptor, int supportedSpiMajor) {
        if (descriptor.spiMajorVersion() != supportedSpiMajor) {
            throw new PluginLoadException("Unsupported SPI major version: "
                    + descriptor.spiApiVersion());
        }
        boolean paymentCapability = descriptor.capabilities().stream()
                .anyMatch(capability -> capability == Capability.DISBURSE
                        || capability == Capability.COLLECT
                        || capability == Capability.QUERY_STATUS);
        boolean riskCapability = descriptor.capabilities().stream()
                .anyMatch(capability -> capability == Capability.CREDIT_SCORE
                        || capability == Capability.BLACKLIST
                        || capability == Capability.FRAUD_SIGNAL);
        boolean mismatch = (descriptor.pluginType() == PluginType.PAYMENT
                && (!paymentCapability || riskCapability))
                || (descriptor.pluginType() == PluginType.RISK_DATA
                && (!riskCapability || paymentCapability));
        if (mismatch) {
            throw new PluginLoadException("Descriptor capabilities do not match plugin type");
        }
    }

    void validateProviderContract(FinBridgePlugin provider, PluginDescriptor descriptor) {
        boolean valid = (descriptor.pluginType() == PluginType.PAYMENT
                && provider instanceof PaymentProvider)
                || (descriptor.pluginType() == PluginType.RISK_DATA
                && provider instanceof RiskDataProvider);
        if (!valid) {
            throw new PluginLoadException("Provider implementation does not match descriptor type");
        }
        validateRiskCapability(provider, descriptor);
    }

    private void validateRiskCapability(FinBridgePlugin provider, PluginDescriptor descriptor) {
        if (provider instanceof RiskDataProvider riskProvider) {
            Capability required = capabilityFor(riskProvider.supportedType());
            if (!descriptor.capabilities().contains(required)) {
                throw new PluginLoadException("Risk provider capability does not match its descriptor");
            }
        }
    }

    boolean closeFailedLoad(FinBridgePlugin provider, PluginClassLoader loader, boolean callbackTimedOut) {
        if (callbackTimedOut) {
            return closeTimedOutLoad(loader);
        }
        closeProviderIfPresent(provider, loader);
        closeLoader(loader);
        lifecycleCallbacks.releaseQuarantine(loader);
        return false;
    }

    private boolean closeTimedOutLoad(PluginClassLoader loader) {
        if (lifecycleCallbacks.canCloseLoader(loader)) {
            closeLoader(loader);
            lifecycleCallbacks.releaseQuarantine(loader);
        }
        return true;
    }

    private void closeProviderIfPresent(FinBridgePlugin provider, PluginClassLoader loader) {
        if (provider == null) {
            return;
        }
        try {
            lifecycleCallbacks.execute("failed-load close", loader, provider::close);
        } catch (PluginLifecycleCallbackException failure) {
            recordFailedLoadCallback(loader, failure);
        } catch (RuntimeException ignored) {
            // Preserve the primary validation failure and continue closing the class loader.
        }
    }

    private void recordFailedLoadCallback(PluginClassLoader loader,
            PluginLifecycleCallbackException failure) {
        if (failure.timedOut() || !lifecycleCallbacks.canCloseLoader(loader)) {
            lifecycleCallbacks.releaseQuarantine(loader);
        }
    }

    private static void closeLoader(PluginClassLoader loader) {
        try {
            loader.close();
        } catch (IOException ignored) {
            // Preserve the primary plugin callback failure.
        }
    }

    static <T> T withContextLoader(ClassLoader loader, PluginCall<T> call) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(loader);
            return call.call();
        } finally {
            thread.setContextClassLoader(original);
        }
    }

}
