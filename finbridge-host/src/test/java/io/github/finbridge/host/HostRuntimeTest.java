package io.github.finbridge.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.finbridge.runtime.PluginRuntime;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HostRuntimeTest {
    @Test
    void createsRuntimeFromApprovedDirectoryWithoutProviderDependencies() {
        PluginRuntime runtime = HostRuntime.create(Path.of("plugins"));
        assertEquals(0, runtime.plugins().size());
    }

    @Test
    void runtimeCanBeCreatedWithoutAdminAuthorityButCannotRunAdminOperations() {
        PluginRuntime runtime = HostRuntime.create(Path.of("plugins"));
        assertEquals(0, runtime.plugins().size());
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                HostAuthorization::requireAdmin);
    }
}
