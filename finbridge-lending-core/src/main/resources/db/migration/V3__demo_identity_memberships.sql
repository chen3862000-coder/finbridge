CREATE TABLE demo_identity_memberships (
    issuer VARCHAR(500) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    principal_subject VARCHAR(160) NOT NULL,
    tenant_id UUID REFERENCES tenants (tenant_id),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    admin BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (issuer, subject),
    CHECK ((admin = TRUE) OR tenant_id IS NOT NULL)
);

CREATE INDEX ix_demo_identity_memberships_tenant
    ON demo_identity_memberships (tenant_id) WHERE enabled;

INSERT INTO demo_identity_memberships (issuer, subject, principal_subject, tenant_id, enabled, admin)
VALUES ('https://localhost:8443/auth/realms/finbridge',
        '10000000-0000-0000-0000-000000000001', 'demo-borrower-alpha',
        '00000000-0000-0000-0000-000000000001', TRUE, FALSE),
       ('https://localhost:8443/auth/realms/finbridge',
        '10000000-0000-0000-0000-000000000002', 'demo-borrower-beta',
        '00000000-0000-0000-0000-000000000002', TRUE, FALSE),
       ('https://localhost:8443/auth/realms/finbridge',
        '10000000-0000-0000-0000-000000000003', 'demo-admin', NULL, TRUE, TRUE);
