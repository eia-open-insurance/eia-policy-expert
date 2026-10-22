-- H2-compatible copy of the Policy expert schema used by component tests.
-- Production DDL lives in src/main/resources/schema.sql; PostgreSQL-only
-- constructs (jsonb, timestamptz, partial unique indexes) are adapted here.
-- JSON payloads are TEXT in H2.

CREATE SEQUENCE IF NOT EXISTS policy_number_seq START 1;
CREATE SEQUENCE IF NOT EXISTS policy_program_number_seq START 1;
CREATE SEQUENCE IF NOT EXISTS policy_credit_note_number_seq START 1;
CREATE SEQUENCE IF NOT EXISTS policy_cancellation_number_seq START 1;

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
    renewed_from_policy_id UUID,
    program_id UUID,
    legal_entity_id UUID,
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

-- H2-compatible copy of the idempotent renewal-lineage ALTER.
ALTER TABLE policy ADD COLUMN IF NOT EXISTS renewed_from_policy_id UUID;

CREATE INDEX IF NOT EXISTS idx_policy_tenant_status ON policy (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_policy_tenant_updated ON policy (tenant_id, updated_at DESC, id);
CREATE INDEX IF NOT EXISTS idx_policy_renewed_from ON policy (tenant_id, renewed_from_policy_id);

-- H2-compatible copy of the commercial program catalog. The CHECK constraints
-- mirror the PostgreSQL DDL; the FK on policy is added after both tables exist.
CREATE TABLE IF NOT EXISTS policy_program (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    program_number VARCHAR(32) NOT NULL,
    name VARCHAR(200) NOT NULL,
    program_type VARCHAR(32) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    capacity_amount NUMERIC(19,4),
    capacity_currency CHAR(3),
    lead_policy_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_policy_program_number UNIQUE (tenant_id, program_number),
    CONSTRAINT ck_policy_program_type CHECK (program_type IN (
        'CAPTIVE', 'POOL', 'FRONTING', 'COINSURANCE', 'MASS_TORT'
    )),
    CONSTRAINT ck_policy_program_status CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT ck_policy_program_dates CHECK (effective_to >= effective_from),
    CONSTRAINT ck_policy_program_capacity
        CHECK (capacity_amount IS NULL OR capacity_amount > 0),
    CONSTRAINT ck_policy_program_capacity_currency
        CHECK (capacity_currency IS NULL OR CHAR_LENGTH(TRIM(capacity_currency)) = 3),
    CONSTRAINT ck_policy_program_capacity_pair
        CHECK ((capacity_amount IS NULL AND capacity_currency IS NULL)
            OR (capacity_amount IS NOT NULL AND capacity_currency IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_policy_program_tenant_created
    ON policy_program (tenant_id, created_at DESC, id);
CREATE INDEX IF NOT EXISTS idx_policy_program_lead_policy
    ON policy_program (tenant_id, lead_policy_id);

-- H2-compatible copy of the optional capacity columns and their idempotent
-- migration/constraints.
ALTER TABLE policy_program ADD COLUMN IF NOT EXISTS capacity_amount NUMERIC(19,4);
ALTER TABLE policy_program ADD COLUMN IF NOT EXISTS capacity_currency CHAR(3);
ALTER TABLE policy_program DROP CONSTRAINT IF EXISTS ck_policy_program_capacity;
ALTER TABLE policy_program ADD CONSTRAINT ck_policy_program_capacity
    CHECK (capacity_amount IS NULL OR capacity_amount > 0);
ALTER TABLE policy_program DROP CONSTRAINT IF EXISTS ck_policy_program_capacity_currency;
ALTER TABLE policy_program ADD CONSTRAINT ck_policy_program_capacity_currency
    CHECK (capacity_currency IS NULL OR CHAR_LENGTH(TRIM(capacity_currency)) = 3);
ALTER TABLE policy_program DROP CONSTRAINT IF EXISTS ck_policy_program_capacity_pair;
ALTER TABLE policy_program ADD CONSTRAINT ck_policy_program_capacity_pair
    CHECK ((capacity_amount IS NULL AND capacity_currency IS NULL)
        OR (capacity_amount IS NOT NULL AND capacity_currency IS NOT NULL));

ALTER TABLE policy ADD COLUMN IF NOT EXISTS program_id UUID;
ALTER TABLE policy DROP CONSTRAINT IF EXISTS fk_policy_program;
ALTER TABLE policy ADD CONSTRAINT fk_policy_program
    FOREIGN KEY (program_id) REFERENCES policy_program (id);

CREATE INDEX IF NOT EXISTS idx_policy_program ON policy (tenant_id, program_id);

-- H2-compatible copy of the legal entity catalog. The CHECK constraints mirror
-- the PostgreSQL DDL; the FK on policy is added after both tables exist.
CREATE TABLE IF NOT EXISTS legal_entity (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(200) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    jurisdiction CHAR(2) NOT NULL,
    regulator VARCHAR(120),
    license_number VARCHAR(80),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_legal_entity_code UNIQUE (tenant_id, code),
    CONSTRAINT ck_legal_entity_type CHECK (entity_type IN (
        'INSURER', 'MGA', 'CAPTIVE', 'POOL', 'FRONTER'
    )),
    CONSTRAINT ck_legal_entity_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_legal_entity_jurisdiction
        CHECK (CHAR_LENGTH(jurisdiction) = 2)
);

CREATE INDEX IF NOT EXISTS idx_legal_entity_tenant_created
    ON legal_entity (tenant_id, created_at DESC, id);

ALTER TABLE policy ADD COLUMN IF NOT EXISTS legal_entity_id UUID;
ALTER TABLE policy DROP CONSTRAINT IF EXISTS fk_policy_legal_entity;
ALTER TABLE policy ADD CONSTRAINT fk_policy_legal_entity
    FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (id);

CREATE INDEX IF NOT EXISTS idx_policy_legal_entity
    ON policy (tenant_id, legal_entity_id);

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
        'CANCELLATION', 'REINSTATEMENT', 'NON_RENEWAL', 'CORRECTION', 'MANUSCRIPT',
        'PREMIUM_AUDIT'
    )),
    CONSTRAINT ck_policy_endorsement_impact
        CHECK (coverage_impact IN ('NONE', 'BROADEN', 'RESTRICT', 'CLARIFY')),
    CONSTRAINT ck_policy_endorsement_status CHECK (status IN ('DRAFT', 'ISSUED', 'VOID'))
);

ALTER TABLE policy_endorsement DROP CONSTRAINT IF EXISTS ck_policy_endorsement_type;
ALTER TABLE policy_endorsement ADD CONSTRAINT ck_policy_endorsement_type
    CHECK (endorsement_type IN (
        'COVERAGE', 'LIMIT', 'PARTY', 'PREMIUM', 'RETENTION', 'EXCLUSION', 'TERRITORY',
        'CANCELLATION', 'REINSTATEMENT', 'NON_RENEWAL', 'CORRECTION', 'MANUSCRIPT',
        'PREMIUM_AUDIT'
    ));

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
    cancellation_number VARCHAR(32),
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
    CONSTRAINT uq_policy_cancellation_number
        UNIQUE (tenant_id, policy_id, cancellation_number),
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

-- H2-compatible copy of the additive cancellation-number migration. The
-- nullable column keeps historical rows valid; the unique constraint treats
-- nulls as distinct, matching PostgreSQL.
ALTER TABLE policy_cancellation
    ADD COLUMN IF NOT EXISTS cancellation_number VARCHAR(32);
ALTER TABLE policy_cancellation
    DROP CONSTRAINT IF EXISTS uq_policy_cancellation_number;
ALTER TABLE policy_cancellation ADD CONSTRAINT uq_policy_cancellation_number
    UNIQUE (tenant_id, policy_id, cancellation_number);

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
    credited_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'DUE',
    paid_at TIMESTAMP WITH TIME ZONE,
    payment_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_policy_installment_number UNIQUE (plan_id, installment_number),
    CONSTRAINT ck_policy_installment_number CHECK (installment_number >= 1),
    CONSTRAINT ck_policy_installment_amount CHECK (amount >= 0),
    CONSTRAINT ck_policy_installment_credited
        CHECK (credited_amount >= 0 AND credited_amount <= amount),
    CONSTRAINT ck_policy_installment_status
        CHECK (status IN ('DUE', 'PAID', 'OVERDUE', 'WAIVED', 'CREDITED'))
);

-- H2-compatible copy of the forward migration: H2 accepts the same
-- ADD COLUMN IF NOT EXISTS and DROP/ADD CONSTRAINT forms.
ALTER TABLE policy_installment
    ADD COLUMN IF NOT EXISTS credited_amount NUMERIC(19,4) NOT NULL DEFAULT 0;
ALTER TABLE policy_installment DROP CONSTRAINT IF EXISTS ck_policy_installment_credited;
ALTER TABLE policy_installment ADD CONSTRAINT ck_policy_installment_credited
    CHECK (credited_amount >= 0 AND credited_amount <= amount);
ALTER TABLE policy_installment DROP CONSTRAINT IF EXISTS ck_policy_installment_status;
ALTER TABLE policy_installment ADD CONSTRAINT ck_policy_installment_status
    CHECK (status IN ('DUE', 'PAID', 'OVERDUE', 'WAIVED', 'CREDITED'));

CREATE INDEX IF NOT EXISTS idx_policy_installment_plan
    ON policy_installment (tenant_id, plan_id, installment_number);

CREATE INDEX IF NOT EXISTS idx_policy_installment_tenant_status_due
    ON policy_installment (tenant_id, status, due_date);

-- H2-compatible copy of the dunning table. The unique (tenant, installment,
-- level) key is the duplicate-send arbiter in both dialects.
CREATE TABLE IF NOT EXISTS billing_dunning_action (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    installment_id UUID NOT NULL REFERENCES policy_installment (id) ON DELETE CASCADE,
    plan_id UUID NOT NULL,
    policy_id UUID NOT NULL,
    level VARCHAR(16) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    notes TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'SENT',
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_billing_dunning_action_level
        UNIQUE (tenant_id, installment_id, level),
    CONSTRAINT ck_billing_dunning_action_level CHECK (level IN (
        'REMINDER', 'FIRST_NOTICE', 'SECOND_NOTICE', 'COLLECTIONS'
    )),
    CONSTRAINT ck_billing_dunning_action_channel
        CHECK (channel IN ('EMAIL', 'LETTER', 'PORTAL')),
    CONSTRAINT ck_billing_dunning_action_status CHECK (status IN ('SENT', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_billing_dunning_action_installment
    ON billing_dunning_action (tenant_id, installment_id, sent_at DESC);
CREATE INDEX IF NOT EXISTS idx_billing_dunning_action_policy
    ON billing_dunning_action (tenant_id, policy_id, sent_at DESC);

-- H2-compatible copy of the credit note table. The CHECK constraints mirror the
-- PostgreSQL DDL; H2 accepts CHAR_LENGTH and the same UUID/FK forms.
CREATE TABLE IF NOT EXISTS policy_credit_note (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    policy_id UUID NOT NULL REFERENCES policy (id) ON DELETE CASCADE,
    note_number VARCHAR(32) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    reason TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    issued_at TIMESTAMP WITH TIME ZONE,
    applied_at TIMESTAMP WITH TIME ZONE,
    applied_installment_id UUID,
    cancellation_id UUID,
    reversed_at TIMESTAMP WITH TIME ZONE,
    reversal_reason VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_policy_credit_note_number UNIQUE (policy_id, note_number),
    CONSTRAINT uq_policy_credit_note_cancellation
        UNIQUE (tenant_id, policy_id, cancellation_id),
    CONSTRAINT ck_policy_credit_note_amount CHECK (amount > 0),
    CONSTRAINT ck_policy_credit_note_reason
        CHECK (reason IS NULL OR CHAR_LENGTH(reason) <= 500),
    CONSTRAINT ck_policy_credit_note_status
        CHECK (status IN ('DRAFT', 'ISSUED', 'APPLIED', 'CANCELLED', 'REVERSED')),
    CONSTRAINT ck_policy_credit_note_issued
        CHECK (status NOT IN ('ISSUED', 'APPLIED') OR issued_at IS NOT NULL),
    CONSTRAINT ck_policy_credit_note_applied
        CHECK (status <> 'APPLIED' OR applied_at IS NOT NULL),
    CONSTRAINT ck_policy_credit_note_reversed
        CHECK (status <> 'REVERSED' OR reversed_at IS NOT NULL),
    CONSTRAINT ck_policy_credit_note_reversal_reason
        CHECK (reversal_reason IS NULL OR CHAR_LENGTH(reversal_reason) <= 500)
);

-- H2-compatible copy of the credit-note cancellation link and its idempotent
-- migration. H2 has no partial unique index, but UNIQUE treats nulls as
-- distinct, so only a real link is unique per tenant/policy.
ALTER TABLE policy_credit_note
    ADD COLUMN IF NOT EXISTS cancellation_id UUID;
ALTER TABLE policy_credit_note
    ADD COLUMN IF NOT EXISTS applied_installment_id UUID;
ALTER TABLE policy_credit_note
    ADD COLUMN IF NOT EXISTS reversed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE policy_credit_note
    ADD COLUMN IF NOT EXISTS reversal_reason VARCHAR(500);
ALTER TABLE policy_credit_note DROP CONSTRAINT IF EXISTS uq_policy_credit_note_cancellation;
ALTER TABLE policy_credit_note ADD CONSTRAINT uq_policy_credit_note_cancellation
    UNIQUE (tenant_id, policy_id, cancellation_id);
ALTER TABLE policy_credit_note DROP CONSTRAINT IF EXISTS ck_policy_credit_note_status;
ALTER TABLE policy_credit_note ADD CONSTRAINT ck_policy_credit_note_status
    CHECK (status IN ('DRAFT', 'ISSUED', 'APPLIED', 'CANCELLED', 'REVERSED'));
ALTER TABLE policy_credit_note DROP CONSTRAINT IF EXISTS ck_policy_credit_note_reversed;
ALTER TABLE policy_credit_note ADD CONSTRAINT ck_policy_credit_note_reversed
    CHECK (status <> 'REVERSED' OR reversed_at IS NOT NULL);
ALTER TABLE policy_credit_note DROP CONSTRAINT IF EXISTS ck_policy_credit_note_reversal_reason;
ALTER TABLE policy_credit_note ADD CONSTRAINT ck_policy_credit_note_reversal_reason
    CHECK (reversal_reason IS NULL OR CHAR_LENGTH(reversal_reason) <= 500);

CREATE INDEX IF NOT EXISTS idx_policy_credit_note_policy
    ON policy_credit_note (tenant_id, policy_id, created_at DESC, note_number DESC);
CREATE INDEX IF NOT EXISTS idx_policy_credit_note_installment
    ON policy_credit_note (tenant_id, applied_installment_id);
CREATE INDEX IF NOT EXISTS idx_policy_credit_note_cancellation
    ON policy_credit_note (tenant_id, cancellation_id);

-- H2-compatible copy of the mass tort profile table. The unique policy_id is
-- the upsert arbiter and the CHECK constraints mirror the PostgreSQL DDL.
CREATE TABLE IF NOT EXISTS policy_mass_tort_profile (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    policy_id UUID NOT NULL REFERENCES policy (id) ON DELETE CASCADE,
    tort_type VARCHAR(32) NOT NULL,
    trigger_type VARCHAR(32) NOT NULL,
    allocation_method VARCHAR(32) NOT NULL,
    allocation_notes TEXT,
    coverage_block_start DATE,
    coverage_block_end DATE,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_policy_mass_tort_profile_policy UNIQUE (policy_id),
    CONSTRAINT ck_policy_mass_tort_profile_tort CHECK (tort_type IN (
        'ASBESTOS', 'SILICA', 'TALC', 'PFAS', 'OPIOIDS', 'PRODUCT_LIABILITY',
        'ENVIRONMENTAL', 'OTHER'
    )),
    CONSTRAINT ck_policy_mass_tort_profile_trigger CHECK (trigger_type IN (
        'EXPOSURE', 'MANIFESTATION', 'INJURY', 'REVISIONIST'
    )),
    CONSTRAINT ck_policy_mass_tort_profile_allocation CHECK (allocation_method IN (
        'ALL_SUMS', 'PRO_RATA_TIME_ON_RISK', 'PRO_RATA_PREMIUM', 'EQUAL_SHARES',
        'STACKED_LIMITS', 'CUSTOM_MATRIX'
    )),
    CONSTRAINT ck_policy_mass_tort_profile_status CHECK (status IN ('DRAFT', 'COMPLETE')),
    CONSTRAINT ck_policy_mass_tort_profile_block CHECK (
        coverage_block_start IS NULL OR coverage_block_end IS NULL
        OR coverage_block_end >= coverage_block_start
    ),
    CONSTRAINT ck_policy_mass_tort_profile_complete CHECK (
        status <> 'COMPLETE'
        OR (tort_type IS NOT NULL AND trigger_type IS NOT NULL
            AND allocation_method IS NOT NULL AND allocation_notes IS NOT NULL
            AND coverage_block_start IS NOT NULL AND coverage_block_end IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_policy_mass_tort_profile_tenant
    ON policy_mass_tort_profile (tenant_id, policy_id);

-- H2-compatible copy of the policy rating worksheet table. The CHECK
-- constraints mirror the PostgreSQL DDL; the FK cascades with the policy.
CREATE TABLE IF NOT EXISTS policy_rating_worksheet (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    policy_id UUID NOT NULL REFERENCES policy (id) ON DELETE CASCADE,
    rating_method VARCHAR(32) NOT NULL,
    base_rate NUMERIC(19,6) NOT NULL,
    exposure_amount NUMERIC(19,4),
    factor NUMERIC(12,6) NOT NULL,
    computed_premium NUMERIC(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    calc_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'CALCULATED',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by UUID NOT NULL,
    CONSTRAINT ck_policy_rating_worksheet_method
        CHECK (rating_method IN ('RATE_TIMES_EXPOSURE', 'FLAT')),
    CONSTRAINT ck_policy_rating_worksheet_base_rate CHECK (base_rate >= 0),
    CONSTRAINT ck_policy_rating_worksheet_factor CHECK (factor >= 0),
    CONSTRAINT ck_policy_rating_worksheet_exposure CHECK (
        (rating_method = 'RATE_TIMES_EXPOSURE'
            AND exposure_amount IS NOT NULL AND exposure_amount > 0)
        OR (rating_method = 'FLAT' AND exposure_amount IS NULL)
    ),
    CONSTRAINT ck_policy_rating_worksheet_premium CHECK (computed_premium >= 0),
    CONSTRAINT ck_policy_rating_worksheet_currency
        CHECK (CHAR_LENGTH(TRIM(currency)) = 3),
    CONSTRAINT ck_policy_rating_worksheet_hash
        CHECK (CHAR_LENGTH(calc_hash) = 64),
    CONSTRAINT ck_policy_rating_worksheet_status CHECK (status = 'CALCULATED')
);

CREATE INDEX IF NOT EXISTS idx_policy_rating_worksheet_policy
    ON policy_rating_worksheet (tenant_id, policy_id, created_at DESC, id);

-- H2-compatible copy of underwriting submission intake. H2 spells the
-- PostgreSQL text[] column as TEXT ARRAY; the CHECK constraints mirror the
-- production DDL.
CREATE TABLE IF NOT EXISTS underwriting_submission_number_seq (
    tenant_id UUID PRIMARY KEY,
    next_value BIGINT NOT NULL,
    CONSTRAINT ck_underwriting_submission_number_seq CHECK (next_value >= 1)
);

CREATE TABLE IF NOT EXISTS underwriting_submission (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    submission_number VARCHAR(32) NOT NULL,
    applicant_party_id UUID NOT NULL,
    product_code VARCHAR(64) NOT NULL,
    requested_effective_date DATE NOT NULL,
    requested_limit_amount NUMERIC(19,4) NOT NULL,
    requested_limit_currency CHAR(3) NOT NULL,
    tort_codes TEXT ARRAY NOT NULL,
    status VARCHAR(16) NOT NULL,
    notes VARCHAR(2000),
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_underwriting_submission_number
        UNIQUE (tenant_id, submission_number),
    CONSTRAINT ck_underwriting_submission_limit
        CHECK (requested_limit_amount >= 0),
    CONSTRAINT ck_underwriting_submission_currency
        CHECK (CHAR_LENGTH(TRIM(requested_limit_currency)) = 3),
    CONSTRAINT ck_underwriting_submission_status CHECK (status IN (
        'RECEIVED', 'REFERRED', 'QUOTED', 'BOUND', 'DECLINED', 'WITHDRAWN'
    ))
);

CREATE INDEX IF NOT EXISTS idx_underwriting_submission_tenant_updated
    ON underwriting_submission (tenant_id, updated_at DESC, id);
CREATE INDEX IF NOT EXISTS idx_underwriting_submission_tenant_status
    ON underwriting_submission (tenant_id, status, updated_at DESC, id);

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
