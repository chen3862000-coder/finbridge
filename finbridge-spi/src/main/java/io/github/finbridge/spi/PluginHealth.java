package io.github.finbridge.spi;

import java.util.Objects;

public record PluginHealth(boolean healthy, String diagnosticCode) {
    public PluginHealth {
        diagnosticCode = Objects.requireNonNull(diagnosticCode, "diagnosticCode");
    }

    public static PluginHealth healthyStatus() {
        return new PluginHealth(true, "HEALTHY");
    }

    public static PluginHealth unhealthy(String diagnosticCode) {
        return new PluginHealth(false, diagnosticCode);
    }
}
