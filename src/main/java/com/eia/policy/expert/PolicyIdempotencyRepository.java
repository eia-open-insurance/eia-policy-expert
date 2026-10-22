package com.eia.policy.expert;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for command idempotency with response replay.
 *
 * <p>A command first reserves its {@code (tenant, operation, key)} row in the
 * same transaction as the business write. Once the command succeeds the row is
 * completed with the serialized response and its original status. A replay
 * reads the completed row and returns the stored body.</p>
 */
@Repository
public class PolicyIdempotencyRepository {
    private final JdbcTemplate jdbc;

    public PolicyIdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PolicyIdempotencyResponse> find(UUID tenantId, String operation, String key) {
        return jdbc.query("""
                        SELECT id, operation, idempotency_key, response_json, response_status,
                               created_at
                          FROM policy_idempotency
                         WHERE tenant_id = ? AND operation = ? AND idempotency_key = ?
                        """,
                (rs, rowNum) -> new PolicyIdempotencyResponse(
                        rs.getObject("id", UUID.class),
                        rs.getString("operation"),
                        rs.getString("idempotency_key"),
                        rs.getString("response_json"),
                        rs.getObject("response_status", Integer.class),
                        rs.getTimestamp("created_at").toInstant()),
                tenantId, operation, key).stream().findFirst();
    }

    /**
     * Reserves the key with one statement. The unique constraint
     * {@code (tenant_id, operation, idempotency_key)} is the concurrency
     * arbiter; {@code ON CONFLICT DO NOTHING} returns zero instead of aborting
     * the transaction, so the losing caller can read the winner's completed row.
     */
    public boolean reserve(UUID id, UUID tenantId, String operation, String key) {
        int rows = jdbc.update("""
                INSERT INTO policy_idempotency
                       (id, tenant_id, operation, idempotency_key, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, id, tenantId, operation, key, Timestamp.from(Instant.now()));
        return rows == 1;
    }

    /**
     * Stores the successful response on the reservation. The row must still
     * exist and be owned by the same tenant/operation/key.
     */
    public void complete(UUID tenantId, String operation, String key, String responseJson,
                         int responseStatus) {
        int rows = jdbc.update("""
                UPDATE policy_idempotency
                   SET response_json = ?, response_status = ?
                 WHERE tenant_id = ? AND operation = ? AND idempotency_key = ?
                """, responseJson, responseStatus, tenantId, operation, key);
        if (rows != 1) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "The idempotency response could not be stored.");
        }
    }
}
