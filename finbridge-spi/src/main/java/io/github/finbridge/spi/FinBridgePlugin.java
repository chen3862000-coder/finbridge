package io.github.finbridge.spi;

public interface FinBridgePlugin extends AutoCloseable {
    PluginDescriptor descriptor();

    default void initialize(PluginContext context) {
    }

    default PluginHealth healthCheck() {
        return PluginHealth.healthyStatus();
    }

    @Override
    default void close() {
    }
}
