CREATE TABLE plugin_artifacts (
    plugin_id VARCHAR(120) NOT NULL,
    plugin_version VARCHAR(80) NOT NULL,
    artifact_name VARCHAR(160) NOT NULL UNIQUE,
    sha256 CHAR(64) NOT NULL,
    plugin_type VARCHAR(32) NOT NULL,
    capabilities TEXT[] NOT NULL,
    desired_state VARCHAR(24) NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (plugin_id, plugin_version),
    CHECK (desired_state IN ('LOADED', 'ACTIVE', 'RETIRED'))
);

CREATE TABLE tenant_plugin_bindings (
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    capability VARCHAR(40) NOT NULL,
    plugin_id VARCHAR(120) NOT NULL,
    plugin_version VARCHAR(80) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    updated_by VARCHAR(160) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, capability),
    FOREIGN KEY (plugin_id, plugin_version)
        REFERENCES plugin_artifacts (plugin_id, plugin_version)
);

CREATE TABLE tenant_plugin_binding_revisions (
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    capability VARCHAR(40) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision >= 0),
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, capability)
);

CREATE TABLE plugin_lifecycle_events (
    event_id UUID PRIMARY KEY,
    plugin_id VARCHAR(120) NOT NULL,
    plugin_version VARCHAR(80) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    actor_id VARCHAR(160) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    diagnostic_code VARCHAR(100) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX ix_plugin_lifecycle_events_key_time
    ON plugin_lifecycle_events (plugin_id, plugin_version, occurred_at DESC);

ALTER TABLE payment_transactions ADD CONSTRAINT ck_payment_transaction_status
    CHECK (status IN ('SUBMISSION_IN_PROGRESS', 'PROCESSING', 'UNKNOWN',
                      'RECONCILIATION_REQUIRED', 'SUCCEEDED', 'FAILED'));
