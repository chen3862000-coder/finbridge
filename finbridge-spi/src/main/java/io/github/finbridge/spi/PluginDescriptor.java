package io.github.finbridge.spi;

import java.util.Objects;
import java.util.Set;

public record PluginDescriptor(
        String pluginId,
        String providerCode,
        String pluginVersion,
        String spiApiVersion,
        PluginType pluginType,
        Set<Capability> capabilities,
        String displayName,
        String description) {

    public PluginDescriptor {
        pluginId = requireText(pluginId, "pluginId");
        providerCode = requireText(providerCode, "providerCode");
        pluginVersion = requireText(pluginVersion, "pluginVersion");
        spiApiVersion = requireText(spiApiVersion, "spiApiVersion");
        pluginType = Objects.requireNonNull(pluginType, "pluginType");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException("At least one capability is required");
        }
        displayName = requireText(displayName, "displayName");
        description = Objects.requireNonNull(description, "description");
    }

    public int spiMajorVersion() {
        int separator = spiApiVersion.indexOf('.');
        String major = separator < 0 ? spiApiVersion : spiApiVersion.substring(0, separator);
        try {
            return Integer.parseInt(major);
        } catch (NumberFormatException invalidVersion) {
            throw new IllegalArgumentException("Invalid SPI API version: " + spiApiVersion,
                    invalidVersion);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
