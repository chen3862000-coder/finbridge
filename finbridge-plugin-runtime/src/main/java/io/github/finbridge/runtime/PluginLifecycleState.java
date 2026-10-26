package io.github.finbridge.runtime;

public enum PluginLifecycleState {
    DISCOVERED,
    VALIDATED,
    LOADED,
    ACTIVE,
    DRAINING,
    UNLOADED,
    FAILED
}
