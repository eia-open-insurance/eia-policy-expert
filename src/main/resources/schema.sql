CREATE SEQUENCE IF NOT EXISTS policy_number_seq START 1;

CREATE TABLE IF NOT EXISTS policy (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    policy_number VARCHAR(32) NOT NULL,
    product_code VARCHAR(32) NOT NULL,
    insured_party_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    status_reason VARCHAR(200),
    effective_date DATE NOT NULL,
    expiration_date DATE NOT NULL,
    premium_amount NUMERIC(19,4) NOT NULL,
    premium_currency CHAR(3) NOT NULL,
    limit_amount NUMERIC(19,4) NOT NULL,
    limit_currency CHAR(3) NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_policy_number UNIQUE (tenant_id, policy_number),
    CONSTRAINT ck_policy_dates CHECK (expiration_date >= effective_date),
    CONSTRAINT ck_policy_premium CHECK (premium_amount >= 0),
    CONSTRAINT ck_policy_limit CHECK (limit_amount >= 0),
    CONSTRAINT ck_policy_status CHECK (status IN (
        'DRAFT', 'QUOTED', 'BOUND', 'ISSUED', 'IN_FORCE', 'ENDORSED',
        'RENEWED', 'CANCELLED', 'EXPIRED', 'NON_RENEWED', 'REINSTATED', 'VOID'
    ))
);

CREATE INDEX IF NOT EXISTS idx_policy_tenant_status ON policy (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_policy_tenant_updated ON policy (tenant_id, updated_at DESC, id);

-- Idempotency scope for POST /internal/v1/policies: tenant + key. The unique
-- constraint is the concurrency arbiter for duplicate creates. The row is
-- written in the same transaction as the policy, so a failed create leaves no
-- reservation behind.
CREATE TABLE IF NOT EXISTS policy_idempotency (
    tenant_id UUID NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    policy_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_policy_idempotency_key UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_policy_idempotency_policy ON policy_idempotency (policy_id);
