package io.github.finbridge.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.spi.PluginType;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class PluginControlPlaneIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"));

    private PluginControlPlaneRepository repository;
    private UUID tenantId;
    private final PluginKey v1 = new PluginKey("control-plane-pay", "1.0.0");
    private final PluginKey v2 = new PluginKey("control-plane-pay", "2.0.0");

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("TRUNCATE TABLE plugin_lifecycle_events, tenant_plugin_bindings, "
                + "tenant_plugin_binding_revisions, plugin_artifacts CASCADE");
        tenantId = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants (tenant_id, tenant_code) VALUES (?, ?)",
                tenantId, "PLUGIN_TEST_" + tenantId.toString().substring(0, 8));
        repository = new PluginControlPlaneRepository(dataSource, Clock.systemUTC());
        repository.registerArtifact("pay-v1.jar", v1, "a".repeat(64), PluginType.PAYMENT,
                Set.of(Capability.DISBURSE, Capability.QUERY_STATUS), "operator");
        repository.registerArtifact("pay-v2.jar", v2, "b".repeat(64), PluginType.PAYMENT,
                Set.of(Capability.DISBURSE, Capability.QUERY_STATUS), "operator");
        repository.setDesiredState(v1, "ACTIVE", "operator", "PLUGIN_ACTIVATED");
        repository.setDesiredState(v2, "ACTIVE", "operator", "PLUGIN_ACTIVATED");
    }

    @Test
    void bindingsUseDurableCompareAndSetRevisionAndRetainRemovalRevision() {
        var first = repository.updateBinding(tenantId, Capability.DISBURSE, v1, 0, "operator");
        assertEquals(1, first.version());
        var second = repository.updateBinding(tenantId, Capability.DISBURSE, v2, 1, "operator");
        assertEquals(2, second.version());
        assertThrows(IllegalStateException.class, () -> repository.updateBinding(
                tenantId, Capability.DISBURSE, v1, 1, "stale-operator"));
        assertEquals(v2, repository.binding(tenantId, Capability.DISBURSE).orElseThrow().pluginKey());
        assertEquals(3, repository.removeBinding(tenantId, Capability.DISBURSE, 2, "operator"));
        assertThrows(IllegalStateException.class, () -> repository.updateBinding(
                tenantId, Capability.DISBURSE, v1, 2, "stale-operator"));
        assertEquals(4, repository.updateBinding(tenantId, Capability.DISBURSE, v1, 3,
                "operator").version());
    }

    @Test
    void artifactIdentityIsImmutableAndLifecycleAuditIsAppendOnly() {
        assertThrows(IllegalStateException.class, () -> repository.registerArtifact(
                "pay-v1.jar", v1, "c".repeat(64), PluginType.PAYMENT,
                Set.of(Capability.DISBURSE), "operator"));

        repository.updateBinding(tenantId, Capability.DISBURSE, v1, 0, "operator");

        var events = repository.lifecycleEvents(v1);
        assertEquals(3, events.size());
        assertEquals("ARTIFACT_REGISTERED", events.getFirst().eventType());
        assertEquals("PLUGIN_ACTIVATED", events.get(1).diagnosticCode());
        assertEquals("BINDING_UPDATED_DISBURSE", events.getLast().diagnosticCode());
    }

    @Test
    void upgradeAndRollbackSwitchFutureBindingsWithDurableRevisionAndDesiredState() {
        repository.updateBinding(tenantId, Capability.DISBURSE, v1, 0, "operator");

        var upgrade = repository.updateBindingAndRetirePrevious(tenantId, Capability.DISBURSE,
                v2, 1, "operator");
        assertEquals(v1, upgrade.previousKey());
        assertEquals(2, upgrade.binding().version());
        assertEquals("RETIRED", repository.findArtifact(v1).orElseThrow().desiredState());
        assertEquals(List.of(), repository.bindings(v1));
        assertEquals(v2, repository.binding(tenantId, Capability.DISBURSE).orElseThrow().pluginKey());

        var rollback = repository.rollbackBindingAndRestorePrevious(tenantId, Capability.DISBURSE,
                v1, 2, "operator");
        assertEquals(v2, rollback.previousKey());
        assertEquals(3, rollback.binding().version());
        assertEquals("ACTIVE", repository.findArtifact(v1).orElseThrow().desiredState());
        assertEquals("RETIRED", repository.findArtifact(v2).orElseThrow().desiredState());
        assertEquals(List.of(rollback.binding()), repository.bindings(tenantId));
        assertEquals(List.of(v1), repository.restorableArtifacts().stream()
                .map(PluginControlPlaneRepository.RegisteredArtifact::key).toList());
    }

    @Test
    void repositoryQueriesRegisteredNamesAndRequiresBindingsToBeRemovedBeforeDrain() {
        repository.updateBinding(tenantId, Capability.DISBURSE, v1, 0, "operator");

        assertEquals(v1, repository.registeredArtifactName("pay-v1.jar").orElseThrow().key());
        assertEquals(v2, repository.findArtifact(v2).orElseThrow().key());
        assertEquals(1, repository.bindings(v1).size());
        assertEquals(List.of(tenantId), repository.bindings().stream()
                .map(io.github.finbridge.runtime.PluginBinding::tenantId).toList());
        assertThrows(IllegalStateException.class, () -> repository.ensureNoBindings(v1));

        repository.removeBinding(tenantId, Capability.DISBURSE, 1, "operator");
        repository.ensureNoBindings(v1);
        assertEquals(List.of(), repository.bindings());
    }

    @Test
    void bindingSwitchReturnsPreviousOwnerAndUsesOptimisticRevision() {
        repository.updateBinding(tenantId, Capability.DISBURSE, v1, 0, "operator");

        var switched = repository.bindingSwitch(tenantId, Capability.DISBURSE, v2, 1, "operator");

        assertEquals(v1, switched.previousKey());
        assertEquals(v2, switched.binding().pluginKey());
        assertEquals(2, switched.binding().version());
        assertThrows(IllegalStateException.class, () -> repository.bindingSwitch(
                tenantId, Capability.DISBURSE, v1, 1, "stale-operator"));
    }

    @Test
    void artifactRegistrationIsIdempotentForIdenticalIdentityAndRejectsRetiredBindingTargets() {
        var registered = repository.registerArtifact("pay-v1.jar", v1, "a".repeat(64), PluginType.PAYMENT,
                Set.of(Capability.DISBURSE, Capability.QUERY_STATUS), "operator");
        assertEquals(v1, registered.key());

        repository.setDesiredState(v2, "RETIRED", "operator", "PLUGIN_RETIRED");
        assertThrows(IllegalStateException.class,
                () -> repository.updateBinding(tenantId, Capability.DISBURSE, v2, 0, "operator"));
        assertThrows(IllegalStateException.class, () -> repository.registerArtifact("pay-v1.jar",
                v1, "c".repeat(64), PluginType.PAYMENT,
                Set.of(Capability.DISBURSE), "operator"));
        assertThrows(IllegalStateException.class, () -> repository.registerArtifact("pay-v1.jar",
                new PluginKey("other-pay", "1.0.0"), "c".repeat(64), PluginType.PAYMENT,
                Set.of(Capability.DISBURSE), "operator"));
    }

    @Test
    void upgradeRepositoryRequiresAnExistingBinding() {
        assertThrows(IllegalStateException.class, () -> repository.updateBindingAndRetirePrevious(
                tenantId, Capability.DISBURSE, v2, 0, "operator"));
    }
}
