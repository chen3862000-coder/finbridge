package io.github.finbridge.runtime;

import io.github.finbridge.spi.PluginKey;

@FunctionalInterface
public interface PluginRetentionPolicy {
    boolean canUnload(PluginKey pluginKey);
}
