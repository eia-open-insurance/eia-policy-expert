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
