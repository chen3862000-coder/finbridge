package io.github.finbridge.demo;

import io.github.finbridge.host.FinBridgePrincipal;
import io.github.finbridge.host.PluginAdminFacade;
import io.github.finbridge.host.PluginControlPlaneRepository;
import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.PluginKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "finbridge.demo.bootstrap-plugins", havingValue = "true")
public class DemoPluginBootstrap implements ApplicationRunner {
    private static final List<ArtifactBinding> FIXTURES = List.of(
            new ArtifactBinding("alphapay-1.0.0-jar-with-dependencies.jar",
                    new PluginKey("alphapay", "1.0.0"), List.of(Capability.DISBURSE, Capability.QUERY_STATUS)),
            new ArtifactBinding("creditscorex-1.0.0-jar-with-dependencies.jar",
                    new PluginKey("creditscorex", "1.0.0"), List.of(Capability.CREDIT_SCORE)),
            new ArtifactBinding("blacklisty-1.0.0-jar-with-dependencies.jar",
                    new PluginKey("blacklisty", "1.0.0"), List.of(Capability.BLACKLIST)));

    private final PluginAdminFacade admin;
    private final Path pluginDirectory;

    public DemoPluginBootstrap(PluginAdminFacade admin,
            @org.springframework.beans.factory.annotation.Value("${finbridge.plugin-directory:plugins}")
            Path pluginDirectory) {
        this.admin = admin;
        this.pluginDirectory = pluginDirectory;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new FinBridgePrincipal("demo-bootstrap", null), "",
                List.of(new SimpleGrantedAuthority("ROLE_FINBRIDGE_ADMIN"))));
        SecurityContextHolder.setContext(context);
        try {
            installAvailableFixtures();
            registerFixtures();
            bindFixtures();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void installAvailableFixtures() {
        for (ArtifactBinding fixture : FIXTURES) {
            if (!Files.isRegularFile(pluginDirectory.resolve(fixture.artifactName()))) {
                continue;
            }
            PluginControlPlaneRepository.RegisteredArtifact artifact = findArtifact(fixture.key());
            if (artifact == null) {
                admin.install(fixture.artifactName());
            }
        }
    }

    private void registerFixtures() {
        for (ArtifactBinding fixture : FIXTURES) {
            PluginControlPlaneRepository.RegisteredArtifact artifact = findArtifact(fixture.key());
            if (artifact != null && !"ACTIVE".equals(artifact.desiredState())) {
                admin.activate(fixture.key());
            }
        }
    }

    private void bindFixtures() {
        for (var tenant : admin.tenants()) {
            List<io.github.finbridge.runtime.PluginBinding> bindings = admin.bindings(tenant.tenantId());
            for (ArtifactBinding fixture : FIXTURES) {
                if (findArtifact(fixture.key()) == null) {
                    continue;
                }
                for (Capability capability : fixture.capabilities()) {
                    boolean exists = bindings.stream().anyMatch(binding -> binding.capability() == capability);
                    if (!exists) {
                        admin.bind(tenant.tenantId(), capability, fixture.key(), 0);
                    }
                }
            }
        }
    }

    private PluginControlPlaneRepository.RegisteredArtifact findArtifact(PluginKey key) {
        return admin.artifacts().stream().filter(artifact -> artifact.key().equals(key)).findFirst().orElse(null);
    }

    private record ArtifactBinding(String artifactName, PluginKey key, List<Capability> capabilities) { }
}
