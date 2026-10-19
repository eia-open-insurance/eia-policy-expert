package com.eia.policy.expert;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the physical schema contract for idempotency. The unique constraint is
 * the concurrency arbiter, so removing it must fail the build.
 */
class SchemaContractTest {
    @Test
    void schemaStoresIdempotencyKeyWithTenantUniqueConstraint() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/schema.sql"));

        assertThat(schema).contains("CREATE TABLE IF NOT EXISTS policy_idempotency");
        assertThat(schema).contains("idempotency_key VARCHAR(255) NOT NULL");
        assertThat(schema).contains("request_hash CHAR(64) NOT NULL");
        assertThat(schema).contains(
                "CONSTRAINT uq_policy_idempotency_key UNIQUE (tenant_id, idempotency_key)");
    }

    @Test
    void schemaIndexesTheStablePageOrder() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/schema.sql"));

        assertThat(schema).contains("idx_policy_tenant_updated");
        assertThat(schema).contains("(tenant_id, updated_at DESC, id)");
    }
}
