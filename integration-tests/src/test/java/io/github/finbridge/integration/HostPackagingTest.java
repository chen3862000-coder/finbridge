package io.github.finbridge.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class HostPackagingTest {
    @Test
    void hostJarExcludesConcreteProviderClassesAndPrivateHelpers() throws Exception {
        Path hostJar = Path.of(System.getProperty("finbridge.root"), "finbridge-host", "target",
                "finbridge-host-0.1.0-SNAPSHOT.jar");
        try (JarFile jar = new JarFile(hostJar.toFile())) {
            assertFalse(jar.stream().anyMatch(entry ->
                    "io/github/finbridge/plugins/alphapay/".equals(entry.getName())));
            assertFalse(jar.stream().anyMatch(entry ->
                    "io/github/finbridge/plugins/privateapi/Helper.class".equals(entry.getName())));
            assertFalse(jar.stream().anyMatch(entry ->
                    "META-INF/services/io.github.finbridge.spi.payment.PaymentProvider"
                            .equals(entry.getName())));
        }
    }
}
