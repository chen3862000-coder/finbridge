package io.github.finbridge.integration;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class ArchitectureBoundaryTest {
    private static final String ALPHAPAY_PACKAGE = "io/github/finbridge/plugins/alphapay/";

    @Test
    void sharedSpiAndRuntimeDoNotDependOnConcreteProviderPackages() {
        var classes = new ClassFileImporter().importPackages(
                "io.github.finbridge.spi", "io.github.finbridge.runtime",
                "io.github.finbridge.host");
        noClasses().that().resideInAnyPackage("io.github.finbridge.spi..",
                        "io.github.finbridge.runtime..", "io.github.finbridge.host..")
                .should().dependOnClassesThat().resideInAnyPackage("io.github.finbridge.plugins.alphapay..")
                .check(classes);
    }

    @Test
    void hostArtifactContainsNoConcreteProviderImplementationClasses() throws Exception {
        Path hostJar = Path.of(System.getProperty("finbridge.root"), "finbridge-host", "target",
                "finbridge-host-0.1.0-SNAPSHOT.jar");
        try (JarFile jar = new JarFile(hostJar.toFile())) {
            assertFalse(jar.stream().anyMatch(entry ->
                    ALPHAPAY_PACKAGE.equals(entry.getName())));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().contains("alphapay-v1")));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().contains("alphapay-v2")));
        }
    }
}
