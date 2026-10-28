package io.github.finbridge.host;

import io.github.finbridge.runtime.PluginBinding;
import io.github.finbridge.spi.Capability;
import io.github.finbridge.spi.PluginKey;
import io.github.finbridge.spi.PluginType;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PluginControlPlaneRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PluginControlPlaneRepository(DataSource dataSource, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        this.clock = clock;
    }

    public RegisteredArtifact registerArtifact(String artifactName, PluginKey key, String checksum,
            PluginType type, Set<Capability> capabilities, String actor) {
        var existing = findArtifact(key);
        if (existing.isPresent()) {
            RegisteredArtifact persisted = existing.orElseThrow();
            if (!persisted.sha256().equals(checksum) || !persisted.artifactName().equals(artifactName)
                    || !persisted.capabilities().equals(capabilities)) {
                throw new IllegalStateException("Plugin identity cannot be replaced with different bytes");
            }
            recordEvent(key, "ARTIFACT_REGISTERED", actor, "ARTIFACT_ALREADY_REGISTERED");
            return persisted;
        }
        var now = timestamp();
        try {
            jdbc.update("INSERT INTO plugin_artifacts (plugin_id, plugin_version, artifact_name, sha256, "
                            + "plugin_type, capabilities, desired_state, registered_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    key.pluginId(), key.pluginVersion(), artifactName, checksum, type.name(),
                    capabilities.stream().map(Enum::name).toArray(String[]::new), "LOADED", now, now);
        } catch (DuplicateKeyException conflict) {
            throw new IllegalStateException("Plugin artifact identity or filename already exists", conflict);
        }
        recordEvent(key, "ARTIFACT_REGISTERED", actor, "ARTIFACT_REGISTERED");
        return findArtifact(key).orElseThrow();
    }

    public List<RegisteredArtifact> restorableArtifacts() {
        return jdbc.query("SELECT artifact.*, EXISTS (SELECT 1 FROM payment_transactions payment "
                        + "WHERE payment.plugin_id = artifact.plugin_id "
                        + "AND payment.plugin_version = artifact.plugin_version "
                        + "AND payment.status IN ('SUBMISSION_IN_PROGRESS', 'PROCESSING', 'UNKNOWN', "
                        + "'RECONCILIATION_REQUIRED')) AS retained_work FROM plugin_artifacts artifact "
                        + "WHERE artifact.desired_state = 'ACTIVE' OR (artifact.desired_state = 'RETIRED' "
                        + "AND EXISTS (SELECT 1 FROM payment_transactions payment "
                        + "WHERE payment.plugin_id = artifact.plugin_id "
                        + "AND payment.plugin_version = artifact.plugin_version "
                        + "AND payment.status IN ('SUBMISSION_IN_PROGRESS', 'PROCESSING', 'UNKNOWN', "
                        + "'RECONCILIATION_REQUIRED'))) ORDER BY artifact.plugin_id, artifact.plugin_version",
                (row, ignored) -> new RegisteredArtifact(row.getString("artifact_name"),
                        new PluginKey(row.getString("plugin_id"), row.getString("plugin_version")),
                        row.getString("sha256"), PluginType.valueOf(row.getString("plugin_type")),
                        capabilities(row.getArray("capabilities")), row.getString("desired_state"),
                        row.getBoolean("retained_work")));
    }

    public List<RegisteredArtifact> artifacts() {
        return jdbc.query("SELECT * FROM plugin_artifacts ORDER BY plugin_id, plugin_version",
                this::mapArtifact);
    }

    public List<TenantSummary> tenants() {
        return jdbc.query("SELECT tenant_id, tenant_code FROM tenants ORDER BY tenant_code",
                (row, ignored) -> new TenantSummary(row.getObject("tenant_id", UUID.class),
                        row.getString("tenant_code")));
    }

    public Optional<RegisteredArtifact> findArtifact(PluginKey key) {
        return jdbc.query("SELECT * FROM plugin_artifacts WHERE plugin_id = ? AND plugin_version = ?",
                this::mapArtifact, key.pluginId(), key.pluginVersion()).stream().findFirst();
    }

    public Optional<RegisteredArtifact> registeredArtifactName(String artifactName) {
        return jdbc.query("SELECT * FROM plugin_artifacts WHERE artifact_name = ?",
                this::mapArtifact, artifactName).stream().findFirst();
    }

    public void setDesiredState(PluginKey key, String state, String actor, String eventCode) {
        OffsetDateTime now = timestamp();
        int updated = jdbc.update("UPDATE plugin_artifacts SET desired_state = ?, updated_at = ? "
                        + "WHERE plugin_id = ? AND plugin_version = ?",
                state, now, key.pluginId(), key.pluginVersion());
        requireOne(updated, "Registered plugin artifact was not found");
        recordEvent(key, eventCode, actor, eventCode);
    }

    public PluginBinding updateBinding(UUID tenantId, Capability capability, PluginKey key,
            long expectedRevision, String actor) {
        return updateBinding(tenantId, capability, key, expectedRevision, actor, "BINDING_UPDATED");
    }

    public BindingSwitch bindingSwitch(UUID tenantId, Capability capability, PluginKey key,
            long expectedRevision, String actor) {
        return transactions.execute(status -> {
            long currentRevision = jdbc.query("SELECT revision FROM tenant_plugin_binding_revisions "
                            + "WHERE tenant_id = ? AND capability = ? FOR UPDATE",
                    (row, ignored) -> row.getLong(1), tenantId, capability.name())
                    .stream().findFirst().orElse(0L);
            if (currentRevision != expectedRevision) {
                throw new IllegalStateException("Stale binding revision; expected " + expectedRevision
                        + " but was " + currentRevision);
            }
            int activeArtifact = jdbc.query("SELECT 1 FROM plugin_artifacts WHERE plugin_id = ? "
                            + "AND plugin_version = ? AND desired_state = 'ACTIVE' AND ? = ANY(capabilities)",
                    (row, ignored) -> row.getInt(1), key.pluginId(), key.pluginVersion(),
                    capability.name()).size();
            requireOne(activeArtifact, "Only active capable artifacts can be bound");
            PluginKey previous = jdbc.query("SELECT plugin_id, plugin_version FROM tenant_plugin_bindings "
                            + "WHERE tenant_id = ? AND capability = ? FOR UPDATE",
                    (row, ignored) -> new PluginKey(row.getString(1), row.getString(2)),
                    tenantId, capability.name()).stream().findFirst().orElse(key);
            PluginBinding binding = updateBindingTransaction(tenantId, capability, key, expectedRevision,
                    actor, "BINDING_UPDATED");
            return new BindingSwitch(binding, previous);
        });
    }

    public PluginBinding updateBinding(UUID tenantId, Capability capability, PluginKey key,
            long expectedRevision, String actor, String eventType) {
        return transactions.execute(status -> updateBindingTransaction(tenantId, capability, key,
                expectedRevision, actor, eventType));
    }

    private PluginBinding updateBindingTransaction(UUID tenantId, Capability capability, PluginKey key,
            long expectedRevision, String actor, String eventType) {
            long currentRevision = lockBindingRevision(tenantId, capability);
            if (currentRevision != expectedRevision) {
                throw new IllegalStateException("Stale binding revision; expected " + expectedRevision
                        + " but was " + currentRevision);
            }
            int activeArtifact = jdbc.query("SELECT 1 FROM plugin_artifacts WHERE plugin_id = ? "
                            + "AND plugin_version = ? AND desired_state = 'ACTIVE' "
                            + "AND ? = ANY(capabilities)",
                    (row, ignored) -> row.getInt(1), key.pluginId(), key.pluginVersion(), capability.name()).size();
            requireOne(activeArtifact, "Only active registered artifacts can be bound");
            long nextRevision = currentRevision + 1;
            int updated;
            boolean bindingExists = !jdbc.query("SELECT 1 FROM tenant_plugin_bindings WHERE tenant_id = ? "
                            + "AND capability = ?", (row, ignored) -> row.getInt(1),
                    tenantId, capability.name()).isEmpty();
            if (bindingExists) {
                updated = jdbc.update("UPDATE tenant_plugin_bindings SET plugin_id = ?, plugin_version = ?, "
                                + "revision = ?, updated_by = ?, updated_at = ? WHERE tenant_id = ? "
                                + "AND capability = ? AND revision = ?",
                        key.pluginId(), key.pluginVersion(), nextRevision, actor, timestamp(),
                        tenantId, capability.name(), currentRevision);
            } else {
                updated = jdbc.update("INSERT INTO tenant_plugin_bindings (tenant_id, capability, plugin_id, "
                                + "plugin_version, revision, updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        tenantId, capability.name(), key.pluginId(), key.pluginVersion(), nextRevision,
                        actor, timestamp());
            }
            requireOne(updated, "Binding update lost compare-and-set race");
            int revisionUpdated = jdbc.update("INSERT INTO tenant_plugin_binding_revisions "
                            + "(tenant_id, capability, revision, updated_at) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT (tenant_id, capability) DO UPDATE SET revision = EXCLUDED.revision, "
                            + "updated_at = EXCLUDED.updated_at WHERE tenant_plugin_binding_revisions.revision = ?",
                    tenantId, capability.name(), nextRevision, timestamp(), currentRevision);
            requireOne(revisionUpdated, "Binding revision compare-and-set failed");
            recordEvent(key, eventType, actor, eventType + "_" + capability.name());
            return new PluginBinding(tenantId, capability, key, nextRevision);
    }

    public BindingSwitch updateBindingAndRetirePrevious(UUID tenantId, Capability capability,
            PluginKey key, long expectedRevision, String actor) {
        return transactions.execute(status -> {
            long currentRevision = lockBindingRevision(tenantId, capability);
            if (currentRevision != expectedRevision) {
                throw new IllegalStateException("Stale binding revision; expected " + expectedRevision
                        + " but was " + currentRevision);
            }
            PluginKey previous = jdbc.query("SELECT plugin_id, plugin_version FROM tenant_plugin_bindings "
                            + "WHERE tenant_id = ? AND capability = ? FOR UPDATE",
                    (row, ignored) -> new PluginKey(row.getString(1), row.getString(2)),
                    tenantId, capability.name()).stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("No current tenant capability binding"));
            int currentRows = jdbc.query("SELECT 1 FROM tenant_plugin_bindings WHERE tenant_id = ? "
                            + "AND capability = ?", (row, ignored) -> row.getInt(1),
                    tenantId, capability.name()).size();
            requireOne(currentRows, "Upgrade requires a current capability binding");
            int targetActive = jdbc.query("SELECT 1 FROM plugin_artifacts WHERE plugin_id = ? "
                            + "AND plugin_version = ? AND desired_state = 'ACTIVE' AND ? = ANY(capabilities)",
                    (row, ignored) -> row.getInt(1), key.pluginId(), key.pluginVersion(), capability.name()).size();
            requireOne(targetActive, "Only active registered artifacts can be bound");
            PluginBinding updated = updateBindingTransaction(tenantId, capability, key, expectedRevision,
                    actor, "PLUGIN_UPGRADE_BINDING_SWITCH");
            if (previous.equals(key)) {
                return new BindingSwitch(updated, previous);
            }
            int remainingBindings = jdbc.query("SELECT COUNT(*) FROM tenant_plugin_bindings "
                            + "WHERE plugin_id = ? AND plugin_version = ?", (row, ignored) -> row.getInt(1),
                    previous.pluginId(), previous.pluginVersion()).getFirst();
            if (remainingBindings == 0 && !previous.equals(key)) {
                jdbc.update("UPDATE plugin_artifacts SET desired_state = 'RETIRED', updated_at = ? "
                                + "WHERE plugin_id = ? AND plugin_version = ?",
                        timestamp(), previous.pluginId(), previous.pluginVersion());
                recordEvent(previous, "PLUGIN_RETIRED", actor, "PLUGIN_UPGRADE_ROUTED_AWAY");
            }
            return new BindingSwitch(updated, previous);
        });
    }

    public BindingSwitch rollbackBindingAndRestorePrevious(UUID tenantId, Capability capability,
            PluginKey target, long expectedRevision, String actor) {
        return transactions.execute(status -> {
            long currentRevision = lockBindingRevision(tenantId, capability);
            if (currentRevision != expectedRevision) {
                throw new IllegalStateException("Stale binding revision; expected " + expectedRevision
                        + " but was " + currentRevision);
            }
            int currentRows = jdbc.query("SELECT 1 FROM tenant_plugin_bindings WHERE tenant_id = ? "
                            + "AND capability = ?", (row, ignored) -> row.getInt(1),
                    tenantId, capability.name()).size();
            requireOne(currentRows, "Rollback requires an existing binding");
            int restoredTarget = jdbc.query("SELECT 1 FROM plugin_artifacts WHERE plugin_id = ? "
                            + "AND plugin_version = ? AND desired_state IN ('ACTIVE', 'RETIRED') "
                            + "AND ? = ANY(capabilities)",
                    (row, ignored) -> row.getInt(1), target.pluginId(), target.pluginVersion(),
                    capability.name()).size();
            requireOne(restoredTarget, "Rollback target artifact is not registered");
            int restored = jdbc.update("UPDATE plugin_artifacts SET desired_state = 'ACTIVE', updated_at = ? "
                            + "WHERE plugin_id = ? AND plugin_version = ? AND desired_state = 'RETIRED'",
                    timestamp(), target.pluginId(), target.pluginVersion());
            if (restored == 1) {
                recordEvent(target, "PLUGIN_REACTIVATED", actor, "PLUGIN_ROLLBACK_TARGET");
            }
            PluginKey previous = jdbc.query("SELECT plugin_id, plugin_version FROM tenant_plugin_bindings "
                            + "WHERE tenant_id = ? AND capability = ?",
                    (row, ignored) -> new PluginKey(row.getString(1), row.getString(2)),
                    tenantId, capability.name()).getFirst();
            PluginBinding updated = updateBindingTransaction(tenantId, capability, target, expectedRevision,
                    actor, "PLUGIN_ROLLBACK_BINDING_SWITCH");
            int remainingBindings = jdbc.query("SELECT COUNT(*) FROM tenant_plugin_bindings "
                            + "WHERE plugin_id = ? AND plugin_version = ?", (row, ignored) -> row.getInt(1),
                    previous.pluginId(), previous.pluginVersion()).getFirst();
            if (remainingBindings == 0 && !previous.equals(target)) {
                jdbc.update("UPDATE plugin_artifacts SET desired_state = 'RETIRED', updated_at = ? "
                                + "WHERE plugin_id = ? AND plugin_version = ?",
                        timestamp(), previous.pluginId(), previous.pluginVersion());
                recordEvent(previous, "PLUGIN_RETIRED", actor, "PLUGIN_ROLLBACK_ROUTED_AWAY");
            }
            return new BindingSwitch(updated, previous);
        });
    }

    public long removeBinding(UUID tenantId, Capability capability, long expectedRevision, String actor) {
        Long removedRevision = transactions.execute(status -> {
            long current = lockBindingRevision(tenantId, capability);
            if (current != expectedRevision) {
                throw new IllegalStateException("Binding revision does not match expected value");
            }
            Optional<PluginKey> previousBinding = jdbc.query("SELECT plugin_id, plugin_version "
                            + "FROM tenant_plugin_bindings "
                            + "WHERE tenant_id = ? AND capability = ?", (row, ignored) ->
                    new PluginKey(row.getString(1), row.getString(2)), tenantId, capability.name())
                    .stream().findFirst();
            if (previousBinding.isEmpty()) {
                throw new IllegalStateException("No binding exists at the expected revision");
            }
            PluginKey previous = previousBinding.orElseThrow();
            requireOne(jdbc.update("DELETE FROM tenant_plugin_bindings WHERE tenant_id = ? "
                            + "AND capability = ?", tenantId, capability.name()),
                    "Binding removal failed");
            int revisionUpdated = jdbc.update("UPDATE tenant_plugin_binding_revisions SET revision = ?, "
                            + "updated_at = ? WHERE tenant_id = ? AND capability = ? AND revision = ?",
                    expectedRevision + 1, timestamp(), tenantId,
                    capability.name(), expectedRevision);
            requireOne(revisionUpdated, "Binding revision compare-and-set failed");
            recordEvent(previous, "BINDING_REMOVED", actor, "BINDING_REMOVED_" + capability.name());
            return expectedRevision + 1;
        });
        return java.util.Objects.requireNonNull(removedRevision, "Binding removal result");
    }

    private long lockBindingRevision(UUID tenantId, Capability capability) {
        jdbc.update("INSERT INTO tenant_plugin_binding_revisions (tenant_id, capability, revision, updated_at) "
                        + "VALUES (?, ?, 0, ?) ON CONFLICT (tenant_id, capability) DO NOTHING",
                tenantId, capability.name(), timestamp());
        return jdbc.query("SELECT revision FROM tenant_plugin_binding_revisions WHERE tenant_id = ? "
                        + "AND capability = ? FOR UPDATE", (row, ignored) -> row.getLong(1),
                tenantId, capability.name()).getFirst();
    }

    public List<PluginBinding> bindings() {
        return jdbc.query("SELECT * FROM tenant_plugin_bindings ORDER BY tenant_id, capability",
                (row, ignored) -> new PluginBinding(row.getObject("tenant_id", UUID.class),
                        Capability.valueOf(row.getString("capability")),
                        new PluginKey(row.getString("plugin_id"), row.getString("plugin_version")),
                        row.getLong("revision")));
    }

    public List<PluginBinding> bindings(PluginKey key) {
        return jdbc.query("SELECT * FROM tenant_plugin_bindings WHERE plugin_id = ? AND plugin_version = ? "
                        + "ORDER BY tenant_id, capability",
                (row, ignored) -> new PluginBinding(row.getObject("tenant_id", UUID.class),
                        Capability.valueOf(row.getString("capability")),
                        new PluginKey(row.getString("plugin_id"), row.getString("plugin_version")),
                        row.getLong("revision")), key.pluginId(), key.pluginVersion());
    }

    public void ensureNoBindings(PluginKey key) {
        int count = jdbc.query("SELECT COUNT(*) FROM tenant_plugin_bindings WHERE plugin_id = ? "
                        + "AND plugin_version = ?", (row, ignored) -> row.getInt(1),
                key.pluginId(), key.pluginVersion()).getFirst();
        if (count != 0) {
            throw new IllegalStateException("Plugin version is still referenced by tenant bindings");
        }
    }

    public Optional<PluginBinding> binding(UUID tenantId, Capability capability) {
        return jdbc.query("SELECT * FROM tenant_plugin_bindings WHERE tenant_id = ? AND capability = ?",
                (row, ignored) -> new PluginBinding(row.getObject("tenant_id", UUID.class),
                        Capability.valueOf(row.getString("capability")),
                        new PluginKey(row.getString("plugin_id"), row.getString("plugin_version")),
                        row.getLong("revision")), tenantId, capability.name()).stream().findFirst();
    }

    public List<PluginBinding> bindings(UUID tenantId) {
        return jdbc.query("SELECT * FROM tenant_plugin_bindings WHERE tenant_id = ? ORDER BY capability",
                (row, ignored) -> new PluginBinding(row.getObject("tenant_id", UUID.class),
                        Capability.valueOf(row.getString("capability")),
                        new PluginKey(row.getString("plugin_id"), row.getString("plugin_version")),
                        row.getLong("revision")), tenantId);
    }

    public List<PluginLifecycleAuditEvent> lifecycleEvents(PluginKey key) {
        return jdbc.query("SELECT * FROM plugin_lifecycle_events WHERE plugin_id = ? "
                        + "AND plugin_version = ? ORDER BY occurred_at, event_id", this::mapEvent,
                key.pluginId(), key.pluginVersion());
    }

    public void recordEvent(PluginKey key, String eventType, String actor, String diagnosticCode) {
        jdbc.update("INSERT INTO plugin_lifecycle_events (event_id, plugin_id, plugin_version, "
                        + "event_type, actor_id, occurred_at, diagnostic_code) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), key.pluginId(), key.pluginVersion(), eventType, actor,
                timestamp(), diagnosticCode);
    }

    private RegisteredArtifact mapArtifact(ResultSet row, int ignoredRow) throws SQLException {
        return new RegisteredArtifact(row.getString("artifact_name"),
                new PluginKey(row.getString("plugin_id"), row.getString("plugin_version")),
                row.getString("sha256"), PluginType.valueOf(row.getString("plugin_type")),
                capabilities(row.getArray("capabilities")), row.getString("desired_state"), false);
    }

    private static Set<Capability> capabilities(Array capabilities) throws SQLException {
        Object[] names = (Object[]) capabilities.getArray();
        Set<Capability> result = EnumSet.noneOf(Capability.class);
        for (Object name : names) {
            result.add(Capability.valueOf(name.toString()));
        }
        return Set.copyOf(result);
    }

    private PluginLifecycleAuditEvent mapEvent(ResultSet row, int ignoredRow) throws SQLException {
        return new PluginLifecycleAuditEvent(row.getObject("event_id", UUID.class),
                new PluginKey(row.getString("plugin_id"), row.getString("plugin_version")),
                row.getString("event_type"), row.getString("actor_id"),
                row.getObject("occurred_at", OffsetDateTime.class).toInstant(), row.getString("diagnostic_code"));
    }

    private OffsetDateTime timestamp() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static void requireOne(int count, String message) {
        if (count != 1) {
            throw new IllegalStateException(message);
        }
    }

    public record RegisteredArtifact(String artifactName, PluginKey key, String sha256,
                                     PluginType type, Set<Capability> capabilities, String desiredState,
                                     boolean retainedWork) {
        public RegisteredArtifact {
            capabilities = Set.copyOf(capabilities);
        }
    }

    public record BindingSwitch(PluginBinding binding, PluginKey previousKey) { }

    public record PluginLifecycleAuditEvent(UUID eventId, PluginKey key, String eventType,
            String actor, java.time.Instant occurredAt, String diagnosticCode) { }

    public record TenantSummary(UUID tenantId, String tenantCode) { }
}
