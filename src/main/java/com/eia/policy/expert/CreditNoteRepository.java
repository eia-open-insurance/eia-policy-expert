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
 * caller treats zero affected rows as a concurrent transition.</p>
 */
@Repository
public class CreditNoteRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, policy_id, note_number, amount, currency, reason, status,
                   issued_at, applied_at, applied_installment_id, created_at
              FROM policy_credit_note
            """;

    private static final RowMapper<PolicyCreditNote> MAPPER = (rs, rowNum) -> {
        Timestamp issuedAt = rs.getTimestamp("issued_at");
        Timestamp appliedAt = rs.getTimestamp("applied_at");
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
                rs.getTimestamp("created_at").toInstant()
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
                 WHERE tenant_id = ? AND policy_id = ?
                 ORDER BY created_at DESC, note_number DESC
                """, MAPPER, tenantId, policyId);
    }

    public Optional<PolicyCreditNote> findById(UUID tenantId, UUID policyId, UUID creditNoteId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ? AND id = ?
                """, MAPPER, tenantId, policyId, creditNoteId).stream().findFirst();
    }

    public void insert(PolicyCreditNote creditNote) {
        jdbc.update("""
                INSERT INTO policy_credit_note
                       (id, tenant_id, policy_id, note_number, amount, currency, reason, status,
                        issued_at, applied_at, applied_installment_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, creditNote.id(), creditNote.tenantId(), creditNote.policyId(),
                creditNote.noteNumber(), creditNote.amount(), creditNote.currency(),
                creditNote.reason(), creditNote.status(),
                creditNote.issuedAt() == null ? null : Timestamp.from(creditNote.issuedAt()),
                creditNote.appliedAt() == null ? null : Timestamp.from(creditNote.appliedAt()),
                creditNote.appliedInstallmentId(),
                Timestamp.from(creditNote.createdAt()));
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
