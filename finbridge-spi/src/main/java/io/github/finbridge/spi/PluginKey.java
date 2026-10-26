package io.github.finbridge.spi;

import java.util.Objects;

public record PluginKey(String pluginId, String pluginVersion) {
    public PluginKey {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(pluginVersion, "pluginVersion");
        if (pluginId.isBlank() || pluginVersion.isBlank()) {
            throw new IllegalArgumentException("Plugin identity values must not be blank");
        }
    }

    public static PluginKey from(PluginDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        return new PluginKey(descriptor.pluginId(), descriptor.pluginVersion());
    }
}
