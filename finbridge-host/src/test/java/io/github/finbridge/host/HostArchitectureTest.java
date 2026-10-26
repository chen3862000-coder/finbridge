package io.github.finbridge.host;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class HostArchitectureTest {
    @Test
    void hostCompositionLayerDoesNotCompileAgainstConcreteProviders() {
        var classes = new ClassFileImporter().importPackages("io.github.finbridge.host");
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("io.github.finbridge.plugins.alphapay..")
                .check(classes);
    }
}
