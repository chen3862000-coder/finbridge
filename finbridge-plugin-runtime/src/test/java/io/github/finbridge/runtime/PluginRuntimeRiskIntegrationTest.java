package io.github.finbridge.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.OperationContext;
import io.github.finbridge.spi.PluginContext;
import io.github.finbridge.spi.risk.CreditScoreValue;
import io.github.finbridge.spi.risk.RiskDataRequest;
import io.github.finbridge.spi.risk.RiskDataType;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginRuntimeRiskIntegrationTest {
    private static final String PROVIDER_JAR =
            "creditscorex-0.1.0-SNAPSHOT-jar-with-dependencies.jar";
    private static final String RISK_SERVICE =
            "META-INF/services/io.github.finbridge.spi.risk.RiskDataProvider";

    @TempDir
    Path plugins;

    @Test
    void installsAndInvokesRiskProviderFromExternalArtifact() throws Exception {
        Path source = Path.of(System.getProperty("finbridge.root"), "plugins", "creditscorex",
                "target", PROVIDER_JAR);
        Path jar = plugins.resolve(PROVIDER_JAR);
        Files.copy(source, jar);
        try (PluginRuntime runtime = new PluginRuntime(plugins,
                new ProviderFreeParent(getClass().getClassLoader()), 1)) {

            PluginHandle provider = runtime.installRiskProvider(PROVIDER_JAR,
                    new PluginContext(Map.of()), RiskDataType.CREDIT_SCORE);

            assertEquals(PluginLifecycleState.LOADED, provider.state());
            assertEquals(Set.of(Capability.CREDIT_SCORE), provider.descriptor().capabilities());
            runtime.activate(provider.key());
            UUID tenant = UUID.randomUUID();
            runtime.bind(tenant, Capability.CREDIT_SCORE, provider.key(), 0);
            try (PluginInvocationLease lease = runtime.route(tenant, Capability.CREDIT_SCORE,
                    InvocationKind.BUSINESS)) {
                var result = lease.invoke(plugin -> ((io.github.finbridge.spi.risk.RiskDataProvider) plugin)
                        .query(new RiskDataRequest(UUID.randomUUID(), tenant, "synthetic-applicant"),
                                new OperationContext(UUID.randomUUID(), "runtime-risk-test",
                                        Instant.now().plusSeconds(2))));
                assertEquals(720, ((CreditScoreValue) result.value()).score());
                assertSame(io.github.finbridge.spi.risk.RiskDataProvider.class,
                        lease.invoke(plugin -> plugin.getClass().getInterfaces()[0]));
            }
        }
    }

    @Test
    void rejectsRiskProviderWhenRequestedTypeDoesNotMatchArtifact() throws Exception {
        Path source = Path.of(System.getProperty("finbridge.root"), "plugins", "creditscorex",
                "target", PROVIDER_JAR);
        Files.copy(source, plugins.resolve(PROVIDER_JAR));
        try (PluginRuntime runtime = new PluginRuntime(plugins,
                new ProviderFreeParent(getClass().getClassLoader()), 1)) {
            PluginLoadException failure = assertThrows(PluginLoadException.class,
                    () -> runtime.installRiskProvider(PROVIDER_JAR,
                            new PluginContext(Map.of()), RiskDataType.BLACKLIST));
            assertTrue(hasCause(failure, "Risk provider does not match requested data capability"));
            assertEquals(java.util.List.of(), runtime.plugins());
        }
    }

    private static boolean hasCause(Throwable failure, String message) {
        Throwable current = failure;
        while (current != null) {
            if (message.equals(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class ProviderFreeParent extends ClassLoader {
        private ProviderFreeParent(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("io.github.finbridge.plugins.")) {
                throw new ClassNotFoundException("Provider must be loaded from its external JAR");
            }
            return super.loadClass(name, resolve);
        }

        @Override
        public URL getResource(String name) {
            return RISK_SERVICE.equals(name) ? null : super.getResource(name);
        }

        @Override
        public java.util.Enumeration<URL> getResources(String name) throws java.io.IOException {
            return RISK_SERVICE.equals(name) ? java.util.Collections.emptyEnumeration()
                    : super.getResources(name);
        }
    }
}
