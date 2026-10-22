package com.eia.policy.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PolicyVersionRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, policy_id, version_number, policy_version_number, change_type,
                   status, effective_from, effective_to, issued_at, issued_by, approved_by,
                   transaction_id, premium_delta_amount, premium_delta_currency, description,
                   supersedes_version_id, immutable_hash, created_at
              FROM policy_version
            """;

    private static final RowMapper<PolicyVersion> MAPPER = (rs, rowNum) -> new PolicyVersion(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("policy_id", UUID.class),
            rs.getInt("version_number"),
            rs.getString("policy_version_number"),
            rs.getString("change_type"),
            rs.getString("status"),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class),
            instant(rs.getTimestamp("issued_at")),
            rs.getObject("issued_by", UUID.class),
            rs.getObject("approved_by", UUID.class),
            rs.getObject("transaction_id", UUID.class),
            rs.getBigDecimal("premium_delta_amount"),
            rs.getString("premium_delta_currency"),
            rs.getString("description"),
            rs.getObject("supersedes_version_id", UUID.class),
            rs.getString("immutable_hash"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public PolicyVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PolicyVersion> findAll(UUID tenantId, UUID policyId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ?
                 ORDER BY version_number DESC
                """, MAPPER, tenantId, policyId);
    }

    public Optional<PolicyVersion> findById(UUID tenantId, UUID policyId, UUID versionId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ? AND id = ?
                """, MAPPER, tenantId, policyId, versionId).stream().findFirst();
    }

    /** The current version is derived: the single ISSUED row, if one exists. */
    public Optional<PolicyVersion> findIssued(UUID tenantId, UUID policyId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ? AND status = 'ISSUED'
                """, MAPPER, tenantId, policyId).stream().findFirst();
    }

    public Optional<PolicyVersion> findLatest(UUID tenantId, UUID policyId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ?
                 ORDER BY version_number DESC
                 LIMIT 1
                """, MAPPER, tenantId, policyId).stream().findFirst();
    }

    public Optional<PolicyVersion> findDraft(UUID tenantId, UUID policyId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ? AND status = 'DRAFT'
                 ORDER BY version_number ASC
                 LIMIT 1
                """, MAPPER, tenantId, policyId).stream().findFirst();
    }

    public int nextVersionNumber(UUID tenantId, UUID policyId) {
        Integer max = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_number), 0) FROM policy_version
                 WHERE tenant_id = ? AND policy_id = ?
                """, Integer.class, tenantId, policyId);
        return (max == null ? 0 : max) + 1;
    }

    public long count(UUID tenantId, UUID policyId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM policy_version WHERE tenant_id = ? AND policy_id = ?
                """, Long.class, tenantId, policyId);
        return count == null ? 0L : count;
    }

    public List<VersionTimeline.Window> findWindows(UUID tenantId, UUID policyId) {
        return jdbc.query("""
                        SELECT id, effective_from, effective_to, status
                          FROM policy_version
                         WHERE tenant_id = ? AND policy_id = ?
                         ORDER BY version_number ASC
                        """,
                (rs, rowNum) -> new VersionTimeline.Window(
                        rs.getObject("id", UUID.class),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class),
                        rs.getString("status")),
                tenantId, policyId);
    }

    public void insert(PolicyVersion version) {
        jdbc.update("""
                INSERT INTO policy_version
                       (id, tenant_id, policy_id, version_number, policy_version_number, change_type,
                        status, effective_from, effective_to, issued_at, issued_by, approved_by,
                        transaction_id, premium_delta_amount, premium_delta_currency, description,
                        supersedes_version_id, immutable_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, version.id(), version.tenantId(), version.policyId(), version.versionNumber(),
                version.policyVersionNumber(), version.changeType(), version.status(),
                version.effectiveFrom(), version.effectiveTo(),
                version.issuedAt() == null ? null : Timestamp.from(version.issuedAt()),
                version.issuedBy(), version.approvedBy(), version.transactionId(),
                version.premiumDeltaAmount(), version.premiumDeltaCurrency(), version.description(),
                version.supersedesVersionId(), version.immutableHash(),
                Timestamp.from(version.createdAt()));
    }

    /**
     * Issues a DRAFT version once. The {@code status = 'DRAFT'} guard is the
     * immutability arbiter for SPEC 11.2.4 ("a version row is immutable after
     * ISSUED"); a second issue attempt updates no rows.
     */
    public int issueDraft(UUID tenantId, UUID policyId, UUID versionId, Instant issuedAt,
                          UUID issuedBy, UUID approvedBy, String immutableHash) {
        return jdbc.update("""
                UPDATE policy_version
                   SET status = 'ISSUED', issued_at = ?, issued_by = ?, approved_by = ?,
                       immutable_hash = ?
                 WHERE tenant_id = ? AND policy_id = ? AND id = ? AND status = 'DRAFT'
                """, Timestamp.from(issuedAt), issuedBy, approvedBy, immutableHash,
                tenantId, policyId, versionId);
    }

    /** Marks the current DRAFT or ISSUED version SUPERSEDED and closes its window. */
    public int supersede(UUID tenantId, UUID policyId, UUID versionId, LocalDate cutover) {
        return jdbc.update("""
                UPDATE policy_version
                   SET status = 'SUPERSEDED',
                       effective_to = CASE
                           WHEN effective_to IS NULL AND CAST(? AS DATE) > effective_from
                               THEN CAST(? AS DATE)
                           ELSE effective_to
                       END
                 WHERE tenant_id = ? AND policy_id = ? AND id = ? AND status IN ('DRAFT', 'ISSUED')
                """, cutover, cutover, tenantId, policyId, versionId);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
