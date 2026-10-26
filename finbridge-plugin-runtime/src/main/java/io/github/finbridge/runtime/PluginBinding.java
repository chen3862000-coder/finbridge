package io.github.finbridge.runtime;

import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.PluginKey;
import java.util.Objects;
import java.util.UUID;

public record PluginBinding(UUID tenantId, Capability capability, PluginKey pluginKey,
                            long version) {
    public PluginBinding {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(pluginKey, "pluginKey");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
    }
}
