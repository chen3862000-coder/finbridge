package io.github.finbridge.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.FinBridgePlugin;
import io.github.finbridge.spi.PluginDescriptor;
import io.github.finbridge.spi.PluginContext;
import io.github.finbridge.spi.PluginType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginDiscoveryLifecycleCallbackTest {
    private static final String ALPHAPAY_JAR = "alphapay-v1-0.1.0-SNAPSHOT-jar-with-dependencies.jar";

    @TempDir
    Path pluginDirectory;

    @Test
    void discoveryCleansFailedLoadsAndClosesTimedOutLoaders() throws Exception {
        var callbacks = new PluginLifecycleCallbackExecutor(Duration.ofMillis(100));
        Path artifact = copyPlugin("discovery-close.jar");
        var discovery = new PluginDiscovery(getClass().getClassLoader(), callbacks);
        var loader = discovery.createLoader(artifact);
        var provider = new io.github.finbridge.plugins.alphapay.v1.AlphaPayV1Provider();
        callbacks.execute("initialize", loader, () -> { });
        assertFalse(discovery.closeFailedLoad(provider, loader, false));

        var timeoutLoader = discovery.createLoader(artifact);
        assertTrue(discovery.closeFailedLoad(null, timeoutLoader, true));
        callbacks.close();
    }

    @Test
    void discoveryClosesLoaderAfterAnUnhealthyProviderFailsLoad() throws Exception {
        var callbacks = new PluginLifecycleCallbackExecutor(Duration.ofMillis(100));
        Path artifact = copyPlugin("unhealthy-provider.jar");
        PluginClassLoader loader = new PluginClassLoader(artifact.toUri().toURL(),
                getClass().getClassLoader());
        var provider = new io.github.finbridge.plugins.alphapay.v1.AlphaPayV1Provider();
        try (callbacks; loader) {
            callbacks.execute("initialize", loader,
                    () -> provider.initialize(new PluginContext(Map.of("test.lifecycle.close-fail", "true"))));
            assertFalse(new PluginDiscovery(getClass().getClassLoader(), callbacks)
                    .closeFailedLoad(provider, loader, false));
        }
    }

    @Test
    void discoveryKeepsLoaderQuarantinedWhenFailedLoadCloseTimesOut() throws Exception {
        try (var callbacks = new PluginLifecycleCallbackExecutor(Duration.ofMillis(100))) {
            Path artifact = copyPlugin("failed-close-timeout.jar");
            PluginClassLoader loader = new PluginClassLoader(artifact.toUri().toURL(),
                    getClass().getClassLoader());
            CountDownLatch closeStarted = new CountDownLatch(1);
            CountDownLatch releaseClose = new CountDownLatch(1);
            FinBridgePlugin provider = new FinBridgePlugin() {
                @Override
                public PluginDescriptor descriptor() {
                    return new PluginDescriptor("slow-close", "slow-close", "1", "1.0",
                            PluginType.PAYMENT, Set.of(Capability.DISBURSE), "Slow close", "Test fixture");
                }

                @Override
                public void close() {
                    closeStarted.countDown();
                    awaitIgnoringInterrupt(releaseClose);
                }
            };

            try {
                assertFalse(new PluginDiscovery(getClass().getClassLoader(), callbacks)
                        .closeFailedLoad(provider, loader, false));
                assertTrue(closeStarted.getCount() == 0);
                assertFalse(callbacks.canCloseLoader(loader));
            } finally {
                releaseClose.countDown();
            }
        }
    }

    @Test
    void discoveryClosesLoaderWhenTimedOutProviderHasNoActiveCallback() throws Exception {
        try (var callbacks = new PluginLifecycleCallbackExecutor(Duration.ofSeconds(1))) {
            Path artifact = copyPlugin("timeout-cleanup.jar");
            PluginClassLoader loader = new PluginClassLoader(artifact.toUri().toURL(),
                    getClass().getClassLoader());
            assertTrue(new PluginDiscovery(getClass().getClassLoader(), callbacks)
                    .closeFailedLoad(null, loader, true));
        }
    }

    @Test
    void discoveryCleansProviderWhenFailedLoadCloseThrows() throws Exception {
        try (var callbacks = new PluginLifecycleCallbackExecutor(Duration.ofSeconds(1))) {
            var discovery = new PluginDiscovery(getClass().getClassLoader(), callbacks);
            Path artifact = copyPlugin("failed-close.jar");
            PluginClassLoader loader = new PluginClassLoader(artifact.toUri().toURL(),
                    getClass().getClassLoader());
            FinBridgePlugin provider = new io.github.finbridge.plugins.alphapay.v1.AlphaPayV1Provider();
            provider.initialize(new PluginContext(Map.of("test.lifecycle.close-fail", "true")));
            assertFalse(discovery.closeFailedLoad(provider, loader, false));
        }
    }

    @Test
    void discoveryClosesTimedOutLoaderAfterTheCallbackIsNoLongerActive() throws Exception {
        try (var callbacks = new PluginLifecycleCallbackExecutor(Duration.ofSeconds(1))) {
            Path artifact = copyPlugin("timeout-loader-cleanup.jar");
            PluginClassLoader loader = new PluginClassLoader(artifact.toUri().toURL(),
                    getClass().getClassLoader());
            PluginDiscovery discovery = new PluginDiscovery(getClass().getClassLoader(), callbacks);
            assertTrue(discovery.closeFailedLoad(null, loader, true));
        }
    }

    private Path copyPlugin(String name) throws Exception {
        Path source = Path.of(System.getProperty("finbridge.root"), "plugins", "alphapay-v1",
                "target", ALPHAPAY_JAR);
        return Files.copy(source, pluginDirectory.resolve(name));
    }

    private static void awaitIgnoringInterrupt(CountDownLatch release) {
        boolean waiting = true;
        while (waiting) {
            try {
                release.await();
                waiting = false;
            } catch (InterruptedException ignored) {
                // This fixture models a trusted callback that ignores interruption.
            }
        }
    }
}
