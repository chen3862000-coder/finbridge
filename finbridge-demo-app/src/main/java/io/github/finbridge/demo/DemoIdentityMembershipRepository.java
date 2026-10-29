package io.github.finbridge.demo;

import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
/** Reads and maintains demo-only issuer/subject memberships used to derive tenant identity. */
public class DemoIdentityMembershipRepository {
    private final JdbcTemplate jdbc;

    public DemoIdentityMembershipRepository(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
    }

    /** Returns an enabled identity mapping; unmapped or disabled subjects remain unauthenticated. */
    public Optional<Membership> findEnabled(String issuer, String subject) {
        return jdbc.query("SELECT principal_subject, tenant_id, admin FROM demo_identity_memberships "
                        + "WHERE issuer = ? AND subject = ? AND enabled = TRUE",
                (row, ignored) -> new Membership(row.getString("principal_subject"),
                        row.getObject("tenant_id", UUID.class), row.getBoolean("admin")),
                issuer, subject).stream().findFirst();
    }

    /** Inserts or refreshes a synthetic local-demo identity membership. */
    public void assignDemoMembership(String issuer, String subject, String principalSubject,
            UUID tenantId, boolean admin) {
        jdbc.update("INSERT INTO demo_identity_memberships (issuer, subject, principal_subject, tenant_id, "
                        + "enabled, admin) VALUES (?, ?, ?, ?, TRUE, ?) ON CONFLICT (issuer, subject) "
                        + "DO UPDATE SET principal_subject = EXCLUDED.principal_subject, "
                        + "tenant_id = EXCLUDED.tenant_id, enabled = TRUE, admin = EXCLUDED.admin",
                issuer, subject, principalSubject, tenantId, admin);
    }

    public record Membership(String principalSubject, UUID tenantId, boolean admin) { }

    public record MembershipKey(String issuer, String subject) implements java.io.Serializable { }
}
