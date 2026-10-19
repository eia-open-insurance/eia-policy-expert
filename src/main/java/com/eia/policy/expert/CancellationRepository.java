package com.eia.policy.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CancellationRepository {
    private static final String CANCELLATION_COLUMNS = """
            SELECT id, tenant_id, policy_id, policy_version_id, cancellation_type, reason,
                   effective_date, notice_days, noticed_at, term_days, remaining_days,
                   pro_rata_pct, earned_premium_amount, return_premium_amount, premium_currency,
                   approval_ref, approved_by, status, created_at
              FROM policy_cancellation
            """;

    private static final RowMapper<PolicyCancellation> CANCELLATION_MAPPER =
            (rs, rowNum) -> new PolicyCancellation(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("policy_id", UUID.class),
                    rs.getObject("policy_version_id", UUID.class),
                    rs.getString("cancellation_type"),
                    rs.getString("reason"),
                    rs.getObject("effective_date", LocalDate.class),
                    rs.getInt("notice_days"),
                    rs.getTimestamp("noticed_at").toInstant(),
                    rs.getInt("term_days"),
                    rs.getInt("remaining_days"),
                    rs.getBigDecimal("pro_rata_pct"),
                    rs.getBigDecimal("earned_premium_amount"),
                    rs.getBigDecimal("return_premium_amount"),
                    rs.getString("premium_currency"),
                    rs.getObject("approval_ref", UUID.class),
                    rs.getObject("approved_by", UUID.class),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant()
            );

    private static final String REINSTATEMENT_COLUMNS = """
            SELECT id, tenant_id, policy_id, cancellation_id, policy_version_id, effective_date,
                   reason, approval_ref, approved_by, premium_evidence_ref,
                   premium_evidence_amount, premium_evidence_currency, status, created_at
              FROM policy_reinstatement
            """;

    private static final RowMapper<PolicyReinstatement> REINSTATEMENT_MAPPER =
            (rs, rowNum) -> new PolicyReinstatement(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("policy_id", UUID.class),
                    rs.getObject("cancellation_id", UUID.class),
                    rs.getObject("policy_version_id", UUID.class),
                    rs.getObject("effective_date", LocalDate.class),
                    rs.getString("reason"),
                    rs.getObject("approval_ref", UUID.class),
                    rs.getObject("approved_by", UUID.class),
                    rs.getString("premium_evidence_ref"),
                    rs.getBigDecimal("premium_evidence_amount"),
                    rs.getString("premium_evidence_currency"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant()
            );

    private final JdbcTemplate jdbc;

    public CancellationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertCancellation(PolicyCancellation cancellation) {
        jdbc.update("""
                INSERT INTO policy_cancellation
                       (id, tenant_id, policy_id, policy_version_id, cancellation_type, reason,
                        effective_date, notice_days, noticed_at, term_days, remaining_days,
                        pro_rata_pct, earned_premium_amount, return_premium_amount,
                        premium_currency, approval_ref, approved_by, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, cancellation.id(), cancellation.tenantId(), cancellation.policyId(),
                cancellation.policyVersionId(), cancellation.cancellationType(),
                cancellation.reason(), cancellation.effectiveDate(), cancellation.noticeDays(),
                Timestamp.from(cancellation.noticedAt()), cancellation.termDays(),
                cancellation.remainingDays(), cancellation.proRataPct(),
                cancellation.earnedPremiumAmount(), cancellation.returnPremiumAmount(),
                cancellation.premiumCurrency(), cancellation.approvalRef(),
                cancellation.approvedBy(), cancellation.status(),
                Timestamp.from(cancellation.createdAt()));
    }

    public Optional<PolicyCancellation> findCancellation(UUID tenantId, UUID policyId,
                                                         UUID cancellationId) {
        return jdbc.query(CANCELLATION_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ? AND id = ?
                """, CANCELLATION_MAPPER, tenantId, policyId, cancellationId).stream().findFirst();
    }

    public Optional<PolicyCancellation> findActiveCancellation(UUID tenantId, UUID policyId) {
        return jdbc.query(CANCELLATION_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ? AND status = 'ACTIVE'
                """, CANCELLATION_MAPPER, tenantId, policyId).stream().findFirst();
    }

    public List<PolicyCancellation> findCancellations(UUID tenantId, UUID policyId) {
        return jdbc.query(CANCELLATION_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ?
                 ORDER BY created_at DESC
                """, CANCELLATION_MAPPER, tenantId, policyId);
    }

    public int markReinstated(UUID tenantId, UUID policyId, UUID cancellationId) {
        return jdbc.update("""
                UPDATE policy_cancellation
                   SET status = 'REINSTATED'
                 WHERE tenant_id = ? AND policy_id = ? AND id = ? AND status = 'ACTIVE'
                """, tenantId, policyId, cancellationId);
    }

    public void insertReinstatement(PolicyReinstatement reinstatement) {
        jdbc.update("""
                INSERT INTO policy_reinstatement
                       (id, tenant_id, policy_id, cancellation_id, policy_version_id, effective_date,
                        reason, approval_ref, approved_by, premium_evidence_ref,
                        premium_evidence_amount, premium_evidence_currency, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, reinstatement.id(), reinstatement.tenantId(), reinstatement.policyId(),
                reinstatement.cancellationId(), reinstatement.policyVersionId(),
                reinstatement.effectiveDate(), reinstatement.reason(),
                reinstatement.approvalRef(), reinstatement.approvedBy(),
                reinstatement.premiumEvidenceRef(), reinstatement.premiumEvidenceAmount(),
                reinstatement.premiumEvidenceCurrency(), reinstatement.status(),
                Timestamp.from(reinstatement.createdAt()));
    }

    public Optional<PolicyReinstatement> findReinstatement(UUID tenantId, UUID policyId,
                                                           UUID reinstatementId) {
        return jdbc.query(REINSTATEMENT_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ? AND id = ?
                """, REINSTATEMENT_MAPPER, tenantId, policyId, reinstatementId).stream().findFirst();
    }

    public List<PolicyReinstatement> findReinstatements(UUID tenantId, UUID policyId) {
        return jdbc.query(REINSTATEMENT_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ?
                 ORDER BY created_at DESC
                """, REINSTATEMENT_MAPPER, tenantId, policyId);
    }
}
