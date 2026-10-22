package com.eia.policy.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for underwriting submission referral history.
 *
 * <p>History rows are tenant-owned children of {@code underwriting_submission}.
 * Reads and writes are tenant-scoped so another tenant's submission id is
 * indistinguishable from a missing one. The parent submission row keeps the
 * latest referral; this repository only appends rounds, decides the newest
 * pending round, and seeds a round for submissions that predate the table.</p>
 */
@Repository
public class SubmissionReferralRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, submission_id, reason, authority, referred_to, notes,
                   decision, decided_by, decided_at, created_at
              FROM underwriting_submission_referral
            """;

    private static final RowMapper<SubmissionReferral> MAPPER =
            (rs, rowNum) -> new SubmissionReferral(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("submission_id", UUID.class),
                    rs.getString("reason"),
                    rs.getString("authority"),
                    rs.getString("referred_to"),
                    rs.getString("notes"),
                    rs.getString("decision"),
                    rs.getString("decided_by"),
                    instant(rs, "decided_at"),
                    rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public SubmissionReferralRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SubmissionReferral referral) {
        jdbc.update("""
                INSERT INTO underwriting_submission_referral
                       (id, tenant_id, submission_id, reason, authority, referred_to, notes,
                        decision, decided_by, decided_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                referral.id(), referral.tenantId(), referral.submissionId(),
                referral.reason(), referral.authority(), referral.referredTo(),
                referral.notes(), referral.decision(), referral.decidedBy(),
                timestamp(referral.decidedAt()), Timestamp.from(referral.createdAt()));
    }

    /** The submission's referral rounds, newest first, with an id tie-breaker. */
    public List<SubmissionReferral> findAllNewestFirst(UUID tenantId, UUID submissionId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND submission_id = ?
                 ORDER BY created_at DESC, id DESC
                """, MAPPER, tenantId, submissionId);
    }

    /**
     * Records the decision on the newest {@code PENDING} round. The
     * {@code PENDING} predicate makes a replay a no-op even if the caller did
     * not notice that the decision was already recorded. A non-null note
     * replaces the round's narrative, matching the submission projection.
     */
    public int decideLatestPending(UUID tenantId, UUID submissionId, String decision,
                                   String decidedBy, String notes, Instant decidedAt) {
        return jdbc.update("""
                UPDATE underwriting_submission_referral
                   SET decision = ?,
                       decided_by = ?,
                       decided_at = ?,
                       notes = COALESCE(?, notes)
                 WHERE id IN (
                       SELECT id
                         FROM underwriting_submission_referral
                        WHERE tenant_id = ? AND submission_id = ?
                          AND decision = 'PENDING'
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1)
                """, decision, decidedBy, Timestamp.from(decidedAt), notes, tenantId,
                submissionId);
    }

    /**
     * Seeds one history round for every submission that still carries a current
     * referral but has no history at all. The current referral columns are the
     * source of truth for such a legacy row: the decision, decider, instant,
     * and narrative are copied as stored, and {@code updated_at} is the best
     * available timestamp for when the current round was last changed. The
     * {@code NOT EXISTS} guard makes repeated startup passes idempotent.
     */
    @Transactional
    public int backfillCurrentReferrals() {
        List<SubmissionReferral> missing = jdbc.query("""
                SELECT s.id AS submission_id, s.tenant_id, s.referral_reason,
                       s.referral_authority, s.referred_to, s.referral_notes,
                       s.referral_decision, s.referral_decided_by, s.referral_decided_at,
                       s.updated_at
                  FROM underwriting_submission s
                 WHERE s.referral_decision IS NOT NULL
                   AND NOT EXISTS (
                       SELECT 1
                         FROM underwriting_submission_referral r
                        WHERE r.submission_id = s.id)
                 ORDER BY s.created_at, s.id
                """, (rs, rowNum) -> new SubmissionReferral(
                        UUID.randomUUID(),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("submission_id", UUID.class),
                        rs.getString("referral_reason"),
                        rs.getString("referral_authority"),
                        rs.getString("referred_to"),
                        rs.getString("referral_notes"),
                        rs.getString("referral_decision"),
                        rs.getString("referral_decided_by"),
                        instant(rs, "referral_decided_at"),
                        rs.getTimestamp("updated_at").toInstant()));
        for (SubmissionReferral referral : missing) {
            insert(referral);
        }
        return missing.size();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
