package com.eia.policy.expert;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PolicyRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, policy_number, product_code, insured_party_id, status,
                   effective_date, expiration_date, premium_amount, premium_currency,
                   limit_amount, limit_currency, version, created_at, updated_at,
                   renewed_from_policy_id
              FROM policy
            """;

    private static final RowMapper<Policy> MAPPER = (rs, rowNum) -> new Policy(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("policy_number"),
            rs.getString("product_code"),
            rs.getObject("insured_party_id", UUID.class),
            rs.getString("status"),
            rs.getObject("effective_date", LocalDate.class),
            rs.getObject("expiration_date", LocalDate.class),
            rs.getBigDecimal("premium_amount"),
            rs.getString("premium_currency"),
            rs.getBigDecimal("limit_amount"),
            rs.getString("limit_currency"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getObject("renewed_from_policy_id", UUID.class)
    );

    private final JdbcTemplate jdbc;

    public PolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Compatibility read: all policies in the stable order used by pagination.
     * New callers should use {@link #findPage(UUID, int, int)}.
     */
    public List<Policy> findAll(UUID tenantId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ?
                 ORDER BY updated_at DESC, id ASC
                """, MAPPER, tenantId);
    }

    /**
     * Stable page ordering: {@code updated_at DESC, id ASC}. The id tie-breaker
     * keeps the order deterministic when two rows share a timestamp.
     */
    public PolicyPageData findPage(UUID tenantId, int page, int size) {
        long offset = (long) page * size;
        List<Policy> items = jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ?
                 ORDER BY updated_at DESC, id ASC
                 LIMIT ? OFFSET ?
                """, MAPPER, tenantId, size, offset);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM policy WHERE tenant_id = ?", Long.class, tenantId);
        return new PolicyPageData(items, total == null ? 0L : total);
    }

    public Optional<Policy> findById(UUID tenantId, UUID id) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND id = ?
                """, MAPPER, tenantId, id).stream().findFirst();
    }

    /** Compatibility create: generates the policy id in the repository. */
    public Policy create(CreatePolicyRequest request) {
        return create(request, UUID.randomUUID());
    }

    public Policy create(CreatePolicyRequest request, UUID policyId) {
        return create(request, policyId, null);
    }

    /**
     * Inserts a policy row, optionally recording the policy it renews. Renewal
     * uses this overload so the predecessor is written atomically with the new
     * policy (SPEC 11.2.4 renewal lineage).
     */
    public Policy create(CreatePolicyRequest request, UUID policyId, UUID renewedFromPolicyId) {
        String status = PolicyRules.normalizeInitialStatus(request.status());
        String policyNumber = request.policyNumber() == null || request.policyNumber().isBlank()
                ? nextPolicyNumber()
                : request.policyNumber().trim();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO policy (id, tenant_id, policy_number, product_code, insured_party_id, status,
                                    effective_date, expiration_date, premium_amount, premium_currency,
                                    limit_amount, limit_currency, renewed_from_policy_id, version,
                                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, policyId, request.tenantId(), policyNumber, request.productCode().trim(),
                request.insuredPartyId(), status, request.effectiveDate(), request.expirationDate(),
                request.premiumAmount(), request.premiumCurrency(), request.limitAmount(),
                request.limitCurrency(), renewedFromPolicyId, Timestamp.from(now),
                Timestamp.from(now));
        return findById(request.tenantId(), policyId).orElseThrow(() -> new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Created policy could not be reloaded."));
    }

    public Policy updateStatus(UUID tenantId, UUID id, String status, String reason, int expectedVersion) {
        Instant now = Instant.now();
        int rows = jdbc.update("""
                UPDATE policy
                   SET status = ?, status_reason = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ?
                """, status, blankToNull(reason), Timestamp.from(now), tenantId, id, expectedVersion);
        if (rows == 0) {
            Policy current = findById(tenantId, id).orElseThrow(() -> new ApiException(
                    HttpStatus.NOT_FOUND, "POLICY_NOT_FOUND", "Policy not found: " + id));
            String message = "Expected version " + expectedVersion
                    + " but policy is at version " + current.version() + "; reload and retry";
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", message,
                    List.of(new ApiException.FieldIssue(
                            "expectedVersion", "STALE_VERSION", message)),
                    Map.of("currentVersion", current.version(), "expectedVersion", expectedVersion));
        }
        return findById(tenantId, id).orElseThrow();
    }

    /**
     * Count of ACTIVE named insureds. The policy cannot be ISSUED or IN_FORCE
     * without at least one (second Policy slice, SPEC 11.2.4 policy_insured).
     */
    public int countNamedInsured(UUID tenantId, UUID id) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM policy_insured
                 WHERE tenant_id = ? AND policy_id = ? AND insured_role = 'NAMED_INSURED'
                   AND status = 'ACTIVE'
                """, Integer.class, tenantId, id);
        return count == null ? 0 : count;
    }

    /**
     * Bumps the aggregate version without a status change. Used by every
     * second-slice material change (coverage, endorsement, insured, location) so
     * the policy ETag still guards all policy commands.
     */
    public int touch(UUID tenantId, UUID id, int expectedVersion) {
        return jdbc.update("""
                UPDATE policy
                   SET version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ?
                """, Timestamp.from(Instant.now()), tenantId, id, expectedVersion);
    }

    public Optional<CommandIdempotencyRecord> findCommandIdempotency(UUID tenantId, String scope,
                                                                     String key) {
        return jdbc.query("""
                        SELECT resource_id, request_hash
                          FROM policy_command_idempotency
                         WHERE tenant_id = ? AND scope = ? AND idempotency_key = ?
                        """,
                (rs, rowNum) -> new CommandIdempotencyRecord(
                        rs.getObject("resource_id", UUID.class),
                        rs.getString("request_hash")),
                tenantId, scope, key).stream().findFirst();
    }

    public boolean insertCommandIdempotencyIfAbsent(UUID tenantId, String scope, String key,
                                                    String requestHash, UUID policyId,
                                                    UUID resourceId) {
        int rows = jdbc.update("""
                INSERT INTO policy_command_idempotency
                       (tenant_id, scope, idempotency_key, request_hash, policy_id, resource_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, scope, idempotency_key) DO NOTHING
                """, tenantId, scope, key, requestHash, policyId, resourceId,
                Timestamp.from(Instant.now()));
        return rows == 1;
    }

    public Optional<IdempotencyRecord> findIdempotency(UUID tenantId, String key) {
        return jdbc.query("""
                        SELECT policy_id, request_hash
                          FROM policy_idempotency
                         WHERE tenant_id = ? AND idempotency_key = ?
                        """,
                (rs, rowNum) -> new IdempotencyRecord(
                        rs.getObject("policy_id", UUID.class),
                        rs.getString("request_hash")),
                tenantId, key).stream().findFirst();
    }

    /**
     * Reserves the idempotency key with a single PostgreSQL statement. The
     * unique constraint {@code (tenant_id, idempotency_key)} decides the winner;
     * {@code ON CONFLICT DO NOTHING} returns zero instead of aborting the
     * transaction, so a concurrent duplicate can be read back safely.
     */
    public boolean insertIdempotencyIfAbsent(UUID tenantId, String key, String requestHash,
                                             UUID policyId) {
        int rows = jdbc.update("""
                INSERT INTO policy_idempotency
                       (tenant_id, idempotency_key, request_hash, policy_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """, tenantId, key, requestHash, policyId, Timestamp.from(Instant.now()));
        return rows == 1;
    }

    private String nextPolicyNumber() {
        Long sequence = jdbc.queryForObject("SELECT nextval('policy_number_seq')", Long.class);
        long value = sequence == null ? 1L : sequence;
        String year = String.valueOf(LocalDate.now(ZoneOffset.UTC).getYear());
        return "POL-" + year + "-" + String.format("%07d", value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
