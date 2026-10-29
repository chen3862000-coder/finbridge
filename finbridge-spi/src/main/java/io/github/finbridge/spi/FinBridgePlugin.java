package io.github.finbridge.spi;

/** Base contract implemented by every independently packaged FinBridge provider. */
public interface FinBridgePlugin extends AutoCloseable {
    /** Returns the stable identity, SPI version, and capabilities advertised by this artifact. */
    PluginDescriptor descriptor();

    /** Initializes the provider after contract validation and before it becomes available. */
    default void initialize(PluginContext context) {
    }

    /** Reports provider readiness; an unhealthy result prevents installation from completing. */
    default PluginHealth healthCheck() {
        return PluginHealth.healthyStatus();
    }

    /** Releases provider resources after invocations drain and the runtime unloads the artifact. */
    @Override
    default void close() {
    }
}
