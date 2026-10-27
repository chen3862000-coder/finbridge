CREATE TABLE tenants (
    tenant_id UUID PRIMARY KEY,
    tenant_code VARCHAR(80) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE loan_products (
    product_code VARCHAR(80) NOT NULL,
    product_version INTEGER NOT NULL CHECK (product_version > 0),
    currency CHAR(3) NOT NULL,
    minimum_principal NUMERIC(19, 2) NOT NULL CHECK (minimum_principal > 0),
    maximum_principal NUMERIC(19, 2) NOT NULL CHECK (maximum_principal >= minimum_principal),
    allowed_terms_months INTEGER[] NOT NULL,
    annual_interest_rate_percent NUMERIC(9, 6) NOT NULL CHECK (annual_interest_rate_percent >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (product_code, product_version)
);

CREATE UNIQUE INDEX uq_loan_product_active
    ON loan_products (product_code) WHERE active;

CREATE TABLE loan_applications (
    application_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    applicant_id VARCHAR(120) NOT NULL,
    product_code VARCHAR(80) NOT NULL,
    product_version INTEGER NOT NULL,
    requested_amount NUMERIC(19, 2) NOT NULL CHECK (requested_amount > 0),
    currency CHAR(3) NOT NULL,
    term_months INTEGER NOT NULL CHECK (term_months > 0),
    status VARCHAR(40) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (product_code, product_version)
        REFERENCES loan_products (product_code, product_version),
    UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX ix_loan_applications_tenant_status
    ON loan_applications (tenant_id, status, created_at DESC);

CREATE TABLE risk_assessments (
    assessment_id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES loan_applications (application_id),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    status VARCHAR(32) NOT NULL,
    credit_score INTEGER,
    blacklist_hit BOOLEAN,
    fraud_signal VARCHAR(80),
    decision VARCHAR(32),
    reason_codes TEXT[] NOT NULL DEFAULT '{}',
    provider_execution_references JSONB NOT NULL DEFAULT '[]'::jsonb,
    policy_version VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE INDEX ix_risk_assessments_application
    ON risk_assessments (tenant_id, application_id, created_at DESC);

CREATE TABLE origination_workflows (
    workflow_id UUID PRIMARY KEY,
    application_id UUID NOT NULL UNIQUE REFERENCES loan_applications (application_id),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    state VARCHAR(32) NOT NULL,
    active_risk_attempt_id UUID,
    active_assessment_id UUID,
    completed_assessment_id UUID REFERENCES risk_assessments (assessment_id),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE origination_workflow_steps (
    attempt_id UUID PRIMARY KEY,
    workflow_id UUID NOT NULL REFERENCES origination_workflows (workflow_id),
    step_name VARCHAR(48) NOT NULL,
    state VARCHAR(24) NOT NULL,
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    assessment_id UUID REFERENCES risk_assessments (assessment_id),
    failure_code VARCHAR(80)
);

ALTER TABLE origination_workflows ADD CONSTRAINT fk_workflow_active_assessment
    FOREIGN KEY (active_assessment_id) REFERENCES risk_assessments (assessment_id);

CREATE INDEX ix_workflow_steps_recovery ON origination_workflow_steps (state, started_at);

CREATE TABLE loans (
    loan_id UUID PRIMARY KEY,
    application_id UUID NOT NULL UNIQUE REFERENCES loan_applications (application_id),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    principal NUMERIC(19, 2) NOT NULL CHECK (principal > 0),
    currency CHAR(3) NOT NULL,
    annual_interest_rate_percent NUMERIC(9, 6) NOT NULL CHECK (annual_interest_rate_percent >= 0),
    term_months INTEGER NOT NULL CHECK (term_months > 0),
    product_code VARCHAR(80) NOT NULL,
    product_version INTEGER NOT NULL,
    status VARCHAR(40) NOT NULL,
    disbursement_transaction_id UUID,
    disbursed_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (product_code, product_version)
        REFERENCES loan_products (product_code, product_version),
    CHECK (status <> 'ACTIVE' OR
        (disbursement_transaction_id IS NOT NULL AND disbursed_at IS NOT NULL AND activated_at IS NOT NULL))
);

CREATE INDEX ix_loans_tenant_status ON loans (tenant_id, status, created_at DESC);

CREATE TABLE repayment_plans (
    plan_id UUID PRIMARY KEY,
    loan_id UUID NOT NULL UNIQUE REFERENCES loans (loan_id),
    tenant_id UUID NOT NULL REFERENCES tenants (tenant_id),
    total_principal NUMERIC(19, 2) NOT NULL CHECK (total_principal > 0),
    total_interest NUMERIC(19, 2) NOT NULL CHECK (total_interest >= 0),
    total_repayable NUMERIC(19, 2) NOT NULL,
    installment_count INTEGER NOT NULL CHECK (installment_count > 0),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ,
    CHECK (total_repayable = total_principal + total_interest),
    CHECK (status <> 'ACTIVE' OR activated_at IS NOT NULL)
);

CREATE TABLE repayment_installments (
    installment_id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES repayment_plans (plan_id),
    installment_number INTEGER NOT NULL CHECK (installment_number > 0),
    due_date DATE,
    opening_principal NUMERIC(19, 2) NOT NULL CHECK (opening_principal >= 0),
    principal_amount NUMERIC(19, 2) NOT NULL CHECK (principal_amount >= 0),
    interest_amount NUMERIC(19, 2) NOT NULL CHECK (interest_amount >= 0),
    total_amount NUMERIC(19, 2) NOT NULL,
    remaining_principal NUMERIC(19, 2) NOT NULL CHECK (remaining_principal >= 0),
    status VARCHAR(32) NOT NULL,
    UNIQUE (plan_id, installment_number),
    CHECK (total_amount = principal_amount + interest_amount)
);

INSERT INTO tenants (tenant_id, tenant_code)
VALUES ('00000000-0000-0000-0000-000000000001', 'DEMO_ALPHA'),
       ('00000000-0000-0000-0000-000000000002', 'DEMO_BETA');

INSERT INTO loan_products (product_code, product_version, currency, minimum_principal,
                           maximum_principal, allowed_terms_months,
                           annual_interest_rate_percent, active)
VALUES ('CASH_LOAN_DEMO', 1, 'CNY', 1000.00, 50000.00,
        ARRAY[3, 6, 12], 12.000000, TRUE);
