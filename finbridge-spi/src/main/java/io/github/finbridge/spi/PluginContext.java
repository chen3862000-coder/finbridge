package io.github.finbridge.spi;

import java.util.Map;
import java.util.Objects;

public record PluginContext(Map<String, String> configuration) {
    public PluginContext {
        configuration = Map.copyOf(Objects.requireNonNull(configuration, "configuration"));
    }
}
