package io.github.finbridge.runtime;

import io.github.finbridge.spi.PluginKey;
import java.time.Instant;
import java.util.Objects;

public record PluginLifecycleEvent(PluginKey pluginKey, PluginLifecycleState state,
                                   Instant occurredAt, String diagnosticCode) {
    public PluginLifecycleEvent {
        Objects.requireNonNull(pluginKey, "pluginKey");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(diagnosticCode, "diagnosticCode");
    }
}
