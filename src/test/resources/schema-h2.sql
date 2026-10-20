-- H2-compatible copy of the Policy expert schema used by component tests.
-- Production DDL lives in src/main/resources/schema.sql; PostgreSQL-only
-- constructs (jsonb, timestamptz, partial unique indexes) are adapted here.
-- JSON payloads are TEXT in H2.

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
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
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

CREATE TABLE IF NOT EXISTS policy_idempotency (
    tenant_id UUID NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    policy_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_policy_idempotency_key UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_policy_idempotency_policy ON policy_idempotency (policy_id);

CREATE TABLE IF NOT EXISTS policy_command_idempotency (
    tenant_id UUID NOT NULL,
    scope VARCHAR(48) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    policy_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_policy_command_idempotency UNIQUE (tenant_id, scope, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_policy_command_idempotency_resource
    ON policy_command_idempotency (policy_id, scope);

CREATE TABLE IF NOT EXISTS policy_version (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    version_number INTEGER NOT NULL,
    policy_version_number VARCHAR(40) NOT NULL,
    change_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    issued_at TIMESTAMP WITH TIME ZONE,
    issued_by UUID,
    approved_by UUID,
    transaction_id UUID,
    premium_delta_amount NUMERIC(19,4),
    premium_delta_currency CHAR(3),
    description TEXT,
    supersedes_version_id UUID,
    immutable_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_policy_version_number UNIQUE (policy_id, version_number),
    CONSTRAINT ck_policy_version_range CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_policy_version_change CHECK (change_type IN (
        'BIND', 'ISSUE', 'ENDORSEMENT', 'RENEWAL', 'CANCELLATION', 'REINSTATEMENT',
        'CORRECTION', 'PREMIUM_AUDIT', 'VOID'
    )),
    CONSTRAINT ck_policy_version_status CHECK (status IN ('DRAFT', 'ISSUED', 'VOIDED', 'SUPERSEDED'))
);

CREATE INDEX IF NOT EXISTS idx_policy_version_policy
    ON policy_version (tenant_id, policy_id, version_number DESC);

CREATE TABLE IF NOT EXISTS policy_coverage (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    coverage_code VARCHAR(32) NOT NULL,
    coverage_name VARCHAR(160) NOT NULL,
    coverage_part VARCHAR(8) NOT NULL,
    occurrence_basis VARCHAR(32) NOT NULL,
    coinsurance_pct NUMERIC(9,6),
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    sequence INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_policy_coverage_dates CHECK (effective_to > effective_from),
    CONSTRAINT ck_policy_coverage_basis CHECK (occurrence_basis IN (
        'OCCURRENCE', 'CLAIMS_MADE', 'CLAIMS_MADE_WITH_RETRO', 'DISCOVERY'
    )),
    CONSTRAINT ck_policy_coverage_status CHECK (status IN ('ACTIVE', 'EXCLUDED', 'ENDORSED', 'EXPIRED')),
    CONSTRAINT ck_policy_coverage_coinsurance
        CHECK (coinsurance_pct IS NULL OR (coinsurance_pct >= 0 AND coinsurance_pct <= 1))
);

CREATE INDEX IF NOT EXISTS idx_policy_coverage_policy ON policy_coverage (tenant_id, policy_id);

CREATE TABLE IF NOT EXISTS policy_limit (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    coverage_id UUID NOT NULL,
    per_occurrence_amount NUMERIC(19,4) NOT NULL,
    aggregate_amount NUMERIC(19,4),
    aggregate_basis VARCHAR(32),
    sublimit_amount NUMERIC(19,4),
    deductible_amount NUMERIC(19,4),
    coinsurance_pct NUMERIC(9,6),
    currency CHAR(3) NOT NULL,
    erosion_basis VARCHAR(32) NOT NULL DEFAULT 'PAID_PLUS_RESERVED',
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL,
    sequence INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_policy_limit_coverage UNIQUE (coverage_id),
    CONSTRAINT ck_policy_limit_amounts CHECK (
        per_occurrence_amount >= 0
        AND (aggregate_amount IS NULL OR aggregate_amount >= 0)
        AND (sublimit_amount IS NULL OR sublimit_amount >= 0)
        AND (deductible_amount IS NULL OR deductible_amount >= 0)
    ),
    CONSTRAINT ck_policy_limit_dates CHECK (effective_to > effective_from),
    CONSTRAINT ck_policy_limit_coinsurance
        CHECK (coinsurance_pct IS NULL OR (coinsurance_pct >= 0 AND coinsurance_pct <= 1)),
    CONSTRAINT ck_policy_limit_erosion
        CHECK (erosion_basis IN ('PAID', 'INCURRED', 'PAID_PLUS_RESERVED')),
    CONSTRAINT ck_policy_limit_aggregate_basis
        CHECK (aggregate_basis IS NULL OR aggregate_basis IN ('PER_POLICY', 'PER_COVERAGE'))
);

CREATE INDEX IF NOT EXISTS idx_policy_limit_policy ON policy_limit (tenant_id, policy_id);

CREATE TABLE IF NOT EXISTS policy_limit_state (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    limit_id UUID NOT NULL,
    as_of TIMESTAMP WITH TIME ZONE NOT NULL,
    original_amount NUMERIC(19,4) NOT NULL,
    paid_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    reserved_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    incurred_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    recovered_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    remaining_amount NUMERIC(19,4) NOT NULL,
    eroded_pct NUMERIC(9,6) NOT NULL DEFAULT 0,
    reinstated_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    source_version BIGINT NOT NULL DEFAULT 0,
    computed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reconciliation_status VARCHAR(16) NOT NULL DEFAULT 'CURRENT',
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_policy_limit_state_limit UNIQUE (limit_id),
    CONSTRAINT ck_policy_limit_state_remaining CHECK (remaining_amount >= 0),
    CONSTRAINT ck_policy_limit_state_status
        CHECK (reconciliation_status IN ('CURRENT', 'STALE', 'DRIFT', 'MANUAL_REVIEW'))
);

CREATE TABLE IF NOT EXISTS policy_limit_erosion (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    limit_id UUID NOT NULL,
    claim_id UUID NOT NULL,
    allocation_id UUID NOT NULL,
    source_version BIGINT NOT NULL,
    paid_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    reserved_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    recovered_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    as_of TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_policy_limit_erosion_fact
        UNIQUE (limit_id, claim_id, allocation_id, source_version),
    CONSTRAINT ck_policy_limit_erosion_amounts CHECK (
        paid_amount >= 0 AND reserved_amount >= 0 AND recovered_amount >= 0
    )
);

CREATE INDEX IF NOT EXISTS idx_policy_limit_erosion_chain
    ON policy_limit_erosion (tenant_id, policy_id, limit_id, source_version DESC);

CREATE TABLE IF NOT EXISTS policy_endorsement (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    policy_version_id UUID NOT NULL,
    endorsement_number VARCHAR(32) NOT NULL,
    sequence INTEGER NOT NULL,
    form_number VARCHAR(32),
    form_edition VARCHAR(16),
    endorsement_type VARCHAR(32) NOT NULL,
    effective_date DATE NOT NULL,
    description TEXT NOT NULL,
    premium_delta_amount NUMERIC(19,4),
    premium_delta_currency CHAR(3),
    coverage_impact VARCHAR(16) NOT NULL,
    coverage_changes TEXT,
    manuscript_flag BOOLEAN NOT NULL DEFAULT FALSE,
    backdate_reason VARCHAR(500),
    approval_ref UUID,
    approved_by UUID,
    status VARCHAR(16) NOT NULL DEFAULT 'ISSUED',
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_policy_endorsement_number UNIQUE (policy_id, endorsement_number),
    CONSTRAINT uq_policy_endorsement_sequence UNIQUE (policy_id, sequence),
    CONSTRAINT ck_policy_endorsement_type CHECK (endorsement_type IN (
        'COVERAGE', 'LIMIT', 'PARTY', 'PREMIUM', 'RETENTION', 'EXCLUSION', 'TERRITORY',
        'CANCELLATION', 'REINSTATEMENT', 'NON_RENEWAL', 'CORRECTION', 'MANUSCRIPT'
    )),
    CONSTRAINT ck_policy_endorsement_impact
        CHECK (coverage_impact IN ('NONE', 'BROADEN', 'RESTRICT', 'CLARIFY')),
    CONSTRAINT ck_policy_endorsement_status CHECK (status IN ('DRAFT', 'ISSUED', 'VOID'))
);

CREATE INDEX IF NOT EXISTS idx_policy_endorsement_policy
    ON policy_endorsement (tenant_id, policy_id, sequence);

CREATE TABLE IF NOT EXISTS policy_insured (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    party_id UUID NOT NULL,
    insured_role VARCHAR(32) NOT NULL,
    display_name VARCHAR(300) NOT NULL,
    certificate_number VARCHAR(32),
    scope VARCHAR(120),
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_policy_insured UNIQUE (policy_id, party_id, insured_role),
    CONSTRAINT ck_policy_insured_role CHECK (insured_role IN (
        'NAMED_INSURED', 'ADDITIONAL_INSURED', 'INSURED_ORGANIZATION', 'LOSS_PAYEE',
        'MORTGAGEE', 'LENDER', 'CERTIFICATE_HOLDER'
    )),
    CONSTRAINT ck_policy_insured_status CHECK (status IN ('ACTIVE', 'ENDORSED_OFF', 'EXPIRED')),
    CONSTRAINT ck_policy_insured_dates CHECK (effective_to > effective_from)
);

CREATE INDEX IF NOT EXISTS idx_policy_insured_policy ON policy_insured (tenant_id, policy_id);

CREATE TABLE IF NOT EXISTS policy_location (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    location_code VARCHAR(32) NOT NULL,
    address_line1 VARCHAR(200) NOT NULL,
    address_line2 VARCHAR(200),
    city VARCHAR(120) NOT NULL,
    state_region VARCHAR(6) NOT NULL,
    postal_code VARCHAR(20),
    country CHAR(3) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_policy_location_code UNIQUE (policy_id, location_code),
    CONSTRAINT ck_policy_location_dates CHECK (effective_to > effective_from)
);

CREATE INDEX IF NOT EXISTS idx_policy_location_policy ON policy_location (tenant_id, policy_id);

CREATE TABLE IF NOT EXISTS policy_cancellation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    policy_version_id UUID NOT NULL,
    cancellation_type VARCHAR(16) NOT NULL,
    reason VARCHAR(200) NOT NULL,
    effective_date DATE NOT NULL,
    notice_days INTEGER NOT NULL DEFAULT 0,
    noticed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    term_days INTEGER NOT NULL,
    remaining_days INTEGER NOT NULL,
    pro_rata_pct NUMERIC(9,6) NOT NULL,
    earned_premium_amount NUMERIC(19,4) NOT NULL,
    return_premium_amount NUMERIC(19,4) NOT NULL,
    premium_currency CHAR(3) NOT NULL,
    approval_ref UUID,
    approved_by UUID,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_policy_cancellation_type
        CHECK (cancellation_type IN ('FLAT', 'PRO_RATA', 'SHORT_RATE', 'NON_PAYMENT', 'VOID')),
    CONSTRAINT ck_policy_cancellation_status
        CHECK (status IN ('ACTIVE', 'REINSTATED', 'VOID')),
    CONSTRAINT ck_policy_cancellation_notice CHECK (notice_days >= 0),
    CONSTRAINT ck_policy_cancellation_amounts CHECK (
        term_days > 0 AND remaining_days >= 0
        AND pro_rata_pct >= 0 AND pro_rata_pct <= 1
        AND earned_premium_amount >= 0 AND return_premium_amount >= 0
    )
);

CREATE INDEX IF NOT EXISTS idx_policy_cancellation_policy
    ON policy_cancellation (tenant_id, policy_id, created_at DESC);

CREATE TABLE IF NOT EXISTS policy_reinstatement (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    cancellation_id UUID NOT NULL,
    policy_version_id UUID NOT NULL,
    effective_date DATE NOT NULL,
    reason VARCHAR(500) NOT NULL,
    approval_ref UUID NOT NULL,
    approved_by UUID NOT NULL,
    premium_evidence_ref VARCHAR(64) NOT NULL,
    premium_evidence_amount NUMERIC(19,4) NOT NULL,
    premium_evidence_currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'APPROVED',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_policy_reinstatement_status CHECK (status IN ('APPROVED', 'REVOKED')),
    CONSTRAINT ck_policy_reinstatement_amount CHECK (premium_evidence_amount >= 0)
);

CREATE INDEX IF NOT EXISTS idx_policy_reinstatement_policy
    ON policy_reinstatement (tenant_id, policy_id, created_at DESC);

-- H2-compatible copy of the billing tables. H2 has no partial unique index, so
-- the "one ACTIVE plan per policy" rule is enforced by BillingService for this
-- dialect; production enforces it with uq_policy_billing_plan_active.
CREATE TABLE IF NOT EXISTS policy_billing_plan (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    policy_id UUID NOT NULL REFERENCES policy (id) ON DELETE CASCADE,
    plan_number VARCHAR(32) NOT NULL,
    total_premium NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    installment_count INTEGER NOT NULL,
    frequency VARCHAR(16) NOT NULL,
    first_due_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_policy_billing_plan_number UNIQUE (policy_id, plan_number),
    CONSTRAINT ck_policy_billing_plan_premium CHECK (total_premium >= 0),
    CONSTRAINT ck_policy_billing_plan_count CHECK (installment_count BETWEEN 1 AND 24),
    CONSTRAINT ck_policy_billing_plan_frequency
        CHECK (frequency IN ('MONTHLY', 'QUARTERLY', 'SEMI_ANNUAL', 'ANNUAL')),
    CONSTRAINT ck_policy_billing_plan_status
        CHECK (status IN ('ACTIVE', 'COMPLETE', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_policy_billing_plan_policy
    ON policy_billing_plan (tenant_id, policy_id, created_at DESC);

CREATE TABLE IF NOT EXISTS policy_installment (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    plan_id UUID NOT NULL REFERENCES policy_billing_plan (id) ON DELETE CASCADE,
    installment_number INTEGER NOT NULL,
    due_date DATE NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DUE',
    paid_at TIMESTAMP WITH TIME ZONE,
    payment_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_policy_installment_number UNIQUE (plan_id, installment_number),
    CONSTRAINT ck_policy_installment_number CHECK (installment_number >= 1),
    CONSTRAINT ck_policy_installment_amount CHECK (amount >= 0),
    CONSTRAINT ck_policy_installment_status
        CHECK (status IN ('DUE', 'PAID', 'OVERDUE', 'WAIVED'))
);

CREATE INDEX IF NOT EXISTS idx_policy_installment_plan
    ON policy_installment (tenant_id, plan_id, installment_number);

-- H2-compatible copy of the PostgreSQL outbox table. JSON payloads are TEXT
-- here; production DDL uses jsonb.
CREATE TABLE IF NOT EXISTS outbox_event (
    id UUID PRIMARY KEY,
    event_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    tenant_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    event_version INTEGER NOT NULL,
    payload TEXT NOT NULL,
    headers TEXT NOT NULL,
    partition_key VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    sent_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_outbox_event_status
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD')),
    CONSTRAINT uq_outbox_event_aggregate_version
        UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);

CREATE INDEX IF NOT EXISTS ix_outbox_event_due
    ON outbox_event (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS ix_outbox_event_partition
    ON outbox_event (partition_key, event_sequence);
