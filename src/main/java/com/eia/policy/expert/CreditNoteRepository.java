package com.eia.policy.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for policy credit notes. Every read is tenant- and policy-scoped,
 * so an id from another tenant or another policy is indistinguishable from a
 * missing one.
 *
 * <p>The status update is conditional on the expected current status; the
 * caller treats zero affected rows as a concurrent transition. Reversal is a
 * separate conditional transition that preserves the application audit columns
 * and writes the reversal stamp and reason.</p>
 */
@Repository
public class CreditNoteRepository {
    private static final String SELECT_COLUMNS = """
            SELECT cn.id, cn.tenant_id, cn.policy_id, cn.note_number, cn.amount, cn.currency,
                   cn.reason, cn.status, cn.issued_at, cn.applied_at, cn.applied_installment_id,
                   cn.created_at, cn.reversed_at, cn.reversal_reason, cn.cancellation_id,
                   COALESCE(pc.cancellation_number, CAST(pc.id AS VARCHAR))
                       AS cancellation_number
              FROM policy_credit_note cn
              LEFT JOIN policy_cancellation pc
                ON pc.tenant_id = cn.tenant_id
               AND pc.policy_id = cn.policy_id
               AND pc.id = cn.cancellation_id
            """;

    private static final RowMapper<PolicyCreditNote> MAPPER = (rs, rowNum) -> {
        Timestamp issuedAt = rs.getTimestamp("issued_at");
        Timestamp appliedAt = rs.getTimestamp("applied_at");
        Timestamp reversedAt = rs.getTimestamp("reversed_at");
        return new PolicyCreditNote(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("policy_id", UUID.class),
                rs.getString("note_number"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("reason"),
                rs.getString("status"),
                issuedAt == null ? null : issuedAt.toInstant(),
                appliedAt == null ? null : appliedAt.toInstant(),
                rs.getObject("applied_installment_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                reversedAt == null ? null : reversedAt.toInstant(),
                rs.getString("reversal_reason"),
                rs.getObject("cancellation_id", UUID.class),
                rs.getString("cancellation_number")
        );
    };

    private final JdbcTemplate jdbc;

    public CreditNoteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Newest first. The note number is a fixed-width monotonic tie-breaker, so
     * two notes created in the same timestamp bucket still list deterministically.
     */
    public List<PolicyCreditNote> findAll(UUID tenantId, UUID policyId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE cn.tenant_id = ? AND cn.policy_id = ?
                 ORDER BY cn.created_at DESC, cn.note_number DESC
                """, MAPPER, tenantId, policyId);
    }

    public Optional<PolicyCreditNote> findById(UUID tenantId, UUID policyId, UUID creditNoteId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE cn.tenant_id = ? AND cn.policy_id = ? AND cn.id = ?
                """, MAPPER, tenantId, policyId, creditNoteId).stream().findFirst();
    }

    /**
     * Tenant- and policy-scoped lookup of the note already linked to a
     * cancellation. The unique constraint is the concurrency arbiter; this read
     * turns the common duplicate into a friendly 409 before the insert.
     */
    public Optional<PolicyCreditNote> findByCancellation(UUID tenantId, UUID policyId,
                                                         UUID cancellationId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE cn.tenant_id = ? AND cn.policy_id = ? AND cn.cancellation_id = ?
                """, MAPPER, tenantId, policyId, cancellationId).stream().findFirst();
    }

    public void insert(PolicyCreditNote creditNote) {
        jdbc.update("""
                INSERT INTO policy_credit_note
                       (id, tenant_id, policy_id, note_number, amount, currency, reason, status,
                        issued_at, applied_at, applied_installment_id, created_at, reversed_at,
                        reversal_reason, cancellation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, creditNote.id(), creditNote.tenantId(), creditNote.policyId(),
                creditNote.noteNumber(), creditNote.amount(), creditNote.currency(),
                creditNote.reason(), creditNote.status(),
                creditNote.issuedAt() == null ? null : Timestamp.from(creditNote.issuedAt()),
                creditNote.appliedAt() == null ? null : Timestamp.from(creditNote.appliedAt()),
                creditNote.appliedInstallmentId(),
                Timestamp.from(creditNote.createdAt()),
                creditNote.reversedAt() == null ? null : Timestamp.from(creditNote.reversedAt()),
                creditNote.reversalReason(),
                creditNote.cancellationId());
    }

    /**
     * Conditional transition: only a row still at {@code expectedStatus} is
     * updated. A concurrent winner makes this return zero. The applied
     * installment id is written by the APPLIED transition and stays null for
     * ISSUED/CANCELLED.
     */
    public int updateStatus(UUID tenantId, UUID policyId, UUID creditNoteId, String status,
                            Instant issuedAt, Instant appliedAt, UUID appliedInstallmentId,
                            String expectedStatus) {
        return jdbc.update("""
                UPDATE policy_credit_note
                   SET status = ?, issued_at = ?, applied_at = ?, applied_installment_id = ?
                 WHERE tenant_id = ? AND policy_id = ? AND id = ? AND status = ?
                """, status,
                issuedAt == null ? null : Timestamp.from(issuedAt),
                appliedAt == null ? null : Timestamp.from(appliedAt),
                appliedInstallmentId,
                tenantId, policyId, creditNoteId, expectedStatus);
    }

    /**
     * Conditional reversal transition: only an APPLIED row is reversed. The
     * issued/applied stamps and the applied installment id are deliberately left
     * untouched so the original application remains auditable; the reversal
     * stamp and reason are written beside them. A concurrent winner returns
     * zero and the caller reports the state conflict.
     */
    public int reverse(UUID tenantId, UUID policyId, UUID creditNoteId, Instant reversedAt,
                       String reversalReason, String expectedStatus) {
        return jdbc.update("""
                UPDATE policy_credit_note
                   SET status = 'REVERSED', reversed_at = ?, reversal_reason = ?
                 WHERE tenant_id = ? AND policy_id = ? AND id = ? AND status = ?
                """, Timestamp.from(reversedAt), reversalReason,
                tenantId, policyId, creditNoteId, expectedStatus);
    }

    /**
     * Server-generated note number, unique per policy because the sequence is
     * global. The fixed width keeps lexicographic ordering aligned with time.
     */
    public String nextNoteNumber() {
        Long sequence = jdbc.queryForObject(
                "SELECT nextval('policy_credit_note_number_seq')", Long.class);
        long value = sequence == null ? 1L : sequence;
        return "CN-" + String.format("%07d", value);
    }
}
