package io.github.finbridge.host;

import io.github.finbridge.runtime.PluginRuntime;
import java.nio.file.Path;

public final class HostRuntime {
    private HostRuntime() {
    }

    public static PluginRuntime create(Path approvedPluginDirectory) {
        return new PluginRuntime(approvedPluginDirectory, HostRuntime.class.getClassLoader(), 1);
    }
}
