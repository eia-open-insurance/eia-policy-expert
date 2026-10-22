package com.eia.policy.expert;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the physical schema contract. The unique constraints are concurrency
 * arbiters, so removing one must fail the build.
 */
class SchemaContractTest {
    @Test
    void schemaStoresIdempotencyKeyWithTenantUniqueConstraint() throws Exception {
        String schema = schema();

        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS policy_idempotency");
        assertThat(schema).contains("idempotency_key VARCHAR(255) NOT NULL");
        assertThat(schema).contains("request_hash CHAR(64) NOT NULL");
        assertThat(schema).contains(
                "CONSTRAINT uq_policy_idempotency_key UNIQUE (tenant_id, idempotency_key)");
    }

    @Test
    void schemaAddsRenewalLineageWithAnIdempotentAlter() throws Exception {
        String schema = schema();

        assertThat(schema).contains("renewed_from_policy_id UUID");
        assertThat(schema).contains(
                "ALTER TABLE policy ADD COLUMN IF NOT EXISTS renewed_from_policy_id UUID");
        assertThat(schema).contains("idx_policy_renewed_from");
    }

    @Test
    void schemaIndexesTheStablePageOrder() throws Exception {
        String schema = schema();

        assertThat(schema).contains("idx_policy_tenant_updated");
        assertThat(schema).contains("(tenant_id, updated_at DESC, id)");
    }

    @Test
    void schemaEnforcesOneIssuedVersionPerPolicy() throws Exception {
        String schema = schema();

        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS policy_version");
        assertThat(schema).contains(
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_policy_version_single_issued");
        assertThat(schema).contains("WHERE status = 'ISSUED'");
        assertThat(schema).contains("CONSTRAINT uq_policy_version_number UNIQUE (policy_id, version_number)");
        assertThat(schema).contains("ck_policy_version_range");
    }

    @Test
    void schemaDefinesTheSecondSliceTables() throws Exception {
        String schema = schema();

        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS policy_coverage");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS policy_limit");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS policy_limit_state");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS policy_limit_erosion");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS policy_endorsement");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS policy_insured");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS policy_location");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS policy_cancellation");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS policy_reinstatement");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS policy_command_idempotency");
    }

    @Test
    void schemaDefinesTheBillingPlanAndInstallmentTables() throws Exception {
        String schema = schema();

        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS policy_billing_plan");
        assertThat(schema).contains("total_premium NUMERIC(19,4) NOT NULL");
        assertThat(schema).contains("installment_count INTEGER NOT NULL");
        assertThat(schema).contains(
                "CHECK (frequency IN ('MONTHLY', 'QUARTERLY', 'SEMI_ANNUAL', 'ANNUAL'))");
        assertThat(schema).contains(
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_policy_billing_plan_active");
        assertThat(schema).contains("WHERE status = 'ACTIVE'");
        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS policy_installment");
        assertThat(schema).contains(
                "CONSTRAINT uq_policy_installment_number UNIQUE (plan_id, installment_number)");
        assertThat(schema).contains("payment_id UUID");
        assertThat(schema).contains("credited_amount NUMERIC(19,4) NOT NULL DEFAULT 0");
        assertThat(schema).contains(
                "CHECK (status IN ('DUE', 'PAID', 'OVERDUE', 'WAIVED', 'CREDITED'))");
        assertThat(schema).contains("CONSTRAINT ck_policy_installment_credited");
        assertThat(schema).contains("CHECK (credited_amount >= 0 AND credited_amount <= amount)");
    }

    @Test
    void schemaDefinesTheMassTortProfileAndItsUpsertArbiter() throws Exception {
        String schema = schema();

        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS policy_mass_tort_profile");
        assertThat(schema).contains("policy_id UUID NOT NULL REFERENCES policy (id)");
        assertThat(schema).contains(
                "CONSTRAINT uq_policy_mass_tort_profile_policy UNIQUE (policy_id)");
        assertThat(schema).contains("tort_type IN (");
        assertThat(schema).contains("'ASBESTOS', 'SILICA', 'TALC', 'PFAS', 'OPIOIDS'");
        assertThat(schema).contains("trigger_type IN (");
        assertThat(schema).contains("'EXPOSURE', 'MANIFESTATION', 'INJURY', 'REVISIONIST'");
        assertThat(schema).contains("allocation_method IN (");
        assertThat(schema).contains("'PRO_RATA_TIME_ON_RISK'");
        assertThat(schema).contains("CONSTRAINT ck_policy_mass_tort_profile_complete");
        assertThat(schema).contains("CONSTRAINT ck_policy_mass_tort_profile_block");
        assertThat(schema).contains("idx_policy_mass_tort_profile_tenant");
    }

    @Test
    void schemaDefinesTheProgramCatalogWithATenantUniqueNumberAndDateCheck() throws Exception {
        String schema = schema();

        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS policy_program");
        assertThat(schema).contains("CREATE SEQUENCE IF NOT EXISTS policy_program_number_seq");
        assertThat(schema).contains(
                "CONSTRAINT uq_policy_program_number UNIQUE (tenant_id, program_number)");
        assertThat(schema).contains(
                "CHECK (program_type IN (\n        'CAPTIVE', 'POOL', 'FRONTING', "
                        + "'COINSURANCE', 'MASS_TORT'\n    ))");
        assertThat(schema).contains("CHECK (status IN ('ACTIVE', 'CLOSED'))");
        assertThat(schema).contains(
                "CONSTRAINT ck_policy_program_dates CHECK (effective_to >= effective_from)");
    }

    @Test
    void schemaAddsTheNullableProgramReferenceWithForeignKeyAndIndex() throws Exception {
        String schema = schema();

        assertThat(schema).contains("ALTER TABLE policy ADD COLUMN IF NOT EXISTS program_id UUID");
        assertThat(schema).contains(
                "ALTER TABLE policy DROP CONSTRAINT IF EXISTS fk_policy_program");
        assertThat(schema).contains("FOREIGN KEY (program_id) REFERENCES policy_program (id)");
        assertThat(schema).contains(
                "CREATE INDEX IF NOT EXISTS idx_policy_program ON policy (tenant_id, program_id)");
    }

    @Test
    void schemaDefinesTheLegalEntityCatalogWithATenantUniqueCode() throws Exception {
        String schema = schema();

        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS legal_entity");
        assertThat(schema).contains("code VARCHAR(32) NOT NULL");
        assertThat(schema).contains("name VARCHAR(200) NOT NULL");
        assertThat(schema).contains("regulator VARCHAR(120)");
        assertThat(schema).contains("license_number VARCHAR(80)");
        assertThat(schema).contains("jurisdiction CHAR(2) NOT NULL");
        assertThat(schema).contains(
                "CONSTRAINT uq_legal_entity_code UNIQUE (tenant_id, code)");
        assertThat(schema).contains(
                "'INSURER', 'MGA', 'CAPTIVE', 'POOL', 'FRONTER'");
        assertThat(schema).contains("CHECK (status IN ('ACTIVE', 'INACTIVE'))");
        assertThat(schema).contains("idx_legal_entity_tenant_created");
    }

    @Test
    void schemaAddsTheNullableLegalEntityReferenceWithForeignKeyAndIndex() throws Exception {
        String schema = schema();

        assertThat(schema).contains(
                "ALTER TABLE policy ADD COLUMN IF NOT EXISTS legal_entity_id UUID");
        assertThat(schema).contains(
                "ALTER TABLE policy DROP CONSTRAINT IF EXISTS fk_policy_legal_entity");
        assertThat(schema).contains(
                "FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (id)");
        assertThat(schema).contains(
                "CREATE INDEX IF NOT EXISTS idx_policy_legal_entity");
    }

    @Test
    void schemaIndexesTheTenantWideReceivableScan() throws Exception {
        String schema = schema();

        assertThat(schema).contains(
                "CREATE INDEX IF NOT EXISTS idx_policy_installment_tenant_status_due");
        assertThat(schema).contains("ON policy_installment (tenant_id, status, due_date)");
    }

    @Test
    void schemaDefinesDunningActionsWithTheLevelArbiter() throws Exception {
        String schema = schema();

        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS billing_dunning_action");
        assertThat(schema).contains(
                "installment_id UUID NOT NULL REFERENCES policy_installment (id)");
        assertThat(schema).contains(
                "CONSTRAINT uq_billing_dunning_action_level");
        assertThat(schema).contains("UNIQUE (tenant_id, installment_id, level)");
        assertThat(schema).contains(
                "'REMINDER', 'FIRST_NOTICE', 'SECOND_NOTICE', 'COLLECTIONS'");
        assertThat(schema).contains("CHECK (channel IN ('EMAIL', 'LETTER', 'PORTAL'))");
        assertThat(schema).contains("CHECK (status IN ('SENT', 'CANCELLED'))");
        assertThat(schema).contains(
                "CREATE INDEX IF NOT EXISTS idx_billing_dunning_action_installment");
        assertThat(schema).contains(
                "CREATE INDEX IF NOT EXISTS idx_billing_dunning_action_policy");
    }

    @Test
    void schemaDefinesCreditNotesWithPerPolicyNumberAndLifecycleChecks() throws Exception {
        String schema = schema();

        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS policy_credit_note");
        assertThat(schema).contains("CREATE SEQUENCE IF NOT EXISTS policy_credit_note_number_seq");
        assertThat(schema).contains("amount NUMERIC(19,4) NOT NULL");
        assertThat(schema).contains(
                "CONSTRAINT uq_policy_credit_note_number UNIQUE (policy_id, note_number)");
        assertThat(schema).contains("CONSTRAINT ck_policy_credit_note_amount CHECK (amount > 0)");
        assertThat(schema).contains(
                "CHECK (status IN ('DRAFT', 'ISSUED', 'APPLIED', 'CANCELLED', 'REVERSED'))");
        assertThat(schema).contains("CONSTRAINT ck_policy_credit_note_issued");
        assertThat(schema).contains("CONSTRAINT ck_policy_credit_note_applied");
        assertThat(schema).contains("CONSTRAINT ck_policy_credit_note_reversed");
        assertThat(schema).contains("issued_at TIMESTAMPTZ");
        assertThat(schema).contains("applied_at TIMESTAMPTZ");
        assertThat(schema).contains("applied_installment_id UUID");
        assertThat(schema).contains(
                "ALTER TABLE policy_credit_note\n    ADD COLUMN IF NOT EXISTS applied_installment_id UUID");
        assertThat(schema).contains("idx_policy_credit_note_policy");
        assertThat(schema).contains("idx_policy_credit_note_installment");
    }

    @Test
    void schemaAddsTheCreditNoteReversalWithAnIdempotentAlter() throws Exception {
        String schema = schema();

        assertThat(schema).contains("reversed_at TIMESTAMPTZ");
        assertThat(schema).contains("reversal_reason VARCHAR(500)");
        assertThat(schema).contains(
                "ALTER TABLE policy_credit_note\n"
                        + "    ADD COLUMN IF NOT EXISTS reversed_at TIMESTAMPTZ");
        assertThat(schema).contains(
                "ALTER TABLE policy_credit_note\n"
                        + "    ADD COLUMN IF NOT EXISTS reversal_reason VARCHAR(500)");
        assertThat(schema).contains(
                "ALTER TABLE policy_credit_note DROP CONSTRAINT IF EXISTS "
                        + "ck_policy_credit_note_status");
        assertThat(schema).contains(
                "ALTER TABLE policy_credit_note ADD CONSTRAINT ck_policy_credit_note_status\n"
                        + "    CHECK (status IN "
                        + "('DRAFT', 'ISSUED', 'APPLIED', 'CANCELLED', 'REVERSED'))");
        assertThat(schema).contains(
                "CHECK (status <> 'REVERSED' OR reversed_at IS NOT NULL)");
    }

    @Test
    void schemaLinksACreditNoteToAtMostOneCancellationWithIdempotentDdl() throws Exception {
        String schema = schema();

        assertThat(schema).contains("cancellation_id UUID");
        assertThat(schema).contains(
                "ALTER TABLE policy_credit_note\n"
                        + "    ADD COLUMN IF NOT EXISTS cancellation_id UUID");
        assertThat(schema).contains(
                "CONSTRAINT uq_policy_credit_note_cancellation");
        assertThat(schema).contains(
                "UNIQUE (tenant_id, policy_id, cancellation_id)");
        assertThat(schema).contains("idx_policy_credit_note_cancellation");

        assertThat(schema).contains("CREATE SEQUENCE IF NOT EXISTS policy_cancellation_number_seq");
        assertThat(schema).contains("cancellation_number VARCHAR(32)");
        assertThat(schema).contains(
                "ALTER TABLE policy_cancellation\n"
                        + "    ADD COLUMN IF NOT EXISTS cancellation_number VARCHAR(32)");
        assertThat(schema).contains("CONSTRAINT uq_policy_cancellation_number");
        assertThat(schema).contains(
                "UNIQUE (tenant_id, policy_id, cancellation_number)");
    }

    @Test
    void schemaMatchesThePlatformTransactionalOutbox() throws Exception {
        String schema = schema();

        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS outbox_event");
        assertThat(schema).contains("event_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE");
        assertThat(schema).contains("payload JSONB NOT NULL");
        assertThat(schema).contains("headers JSONB NOT NULL DEFAULT '{}'::jsonb");
        assertThat(schema).contains("partition_key VARCHAR(128) NOT NULL");
        assertThat(schema).contains(
                "CONSTRAINT uq_outbox_event_aggregate_version");
        assertThat(schema).contains(
                "UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)");
        assertThat(schema).contains("ix_outbox_event_due");
        assertThat(schema).contains("ix_outbox_event_partition");
    }

    @Test
    void schemaEnforcesLimitAndErosionInvariantsInTheDatabase() throws Exception {
        String schema = schema();

        assertThat(schema).contains("CONSTRAINT ck_policy_limit_amounts");
        assertThat(schema).contains("CONSTRAINT ck_policy_limit_state_remaining CHECK (remaining_amount >= 0)");
        assertThat(schema).contains(
                "CONSTRAINT uq_policy_limit_erosion_fact");
        assertThat(schema).contains(
                "UNIQUE (limit_id, claim_id, allocation_id, source_version)");
        assertThat(schema).contains(
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_policy_cancellation_active");
        assertThat(schema).contains(
                "CONSTRAINT uq_policy_insured UNIQUE (policy_id, party_id, insured_role)");
        assertThat(schema).contains(
                "CONSTRAINT uq_policy_location_code UNIQUE (policy_id, location_code)");
        assertThat(schema).contains(
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_policy_coverage_active_code");
    }

    private static String schema() throws Exception {
        return Files.readString(Path.of("src/main/resources/schema.sql"));
    }
}
