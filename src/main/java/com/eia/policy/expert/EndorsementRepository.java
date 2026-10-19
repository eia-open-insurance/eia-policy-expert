package com.eia.policy.expert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EndorsementRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, policy_id, policy_version_id, endorsement_number, sequence,
                   form_number, form_edition, endorsement_type, effective_date, description,
                   premium_delta_amount, premium_delta_currency, coverage_impact, coverage_changes,
                   manuscript_flag, backdate_reason, approval_ref, approved_by, status, issued_at,
                   created_at
              FROM policy_endorsement
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EndorsementRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(PolicyEndorsement endorsement) {
        jdbc.update("""
                INSERT INTO policy_endorsement
                       (id, tenant_id, policy_id, policy_version_id, endorsement_number, sequence,
                        form_number, form_edition, endorsement_type, effective_date, description,
                        premium_delta_amount, premium_delta_currency, coverage_impact,
                        coverage_changes, manuscript_flag, backdate_reason, approval_ref,
                        approved_by, status, issued_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, endorsement.id(), endorsement.tenantId(), endorsement.policyId(),
                endorsement.policyVersionId(), endorsement.endorsementNumber(),
                endorsement.sequence(), endorsement.formNumber(), endorsement.formEdition(),
                endorsement.endorsementType(), endorsement.effectiveDate(),
                endorsement.description(), endorsement.premiumDeltaAmount(),
                endorsement.premiumDeltaCurrency(), endorsement.coverageImpact(),
                writeChanges(endorsement.coverageChanges()), endorsement.manuscriptFlag(),
                endorsement.backdateReason(), endorsement.approvalRef(), endorsement.approvedBy(),
                endorsement.status(), Timestamp.from(endorsement.issuedAt()),
                Timestamp.from(endorsement.createdAt()));
    }

    public Optional<PolicyEndorsement> findById(UUID tenantId, UUID policyId, UUID endorsementId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ? AND id = ?
                """, mapper(), tenantId, policyId, endorsementId).stream().findFirst();
    }

    public List<PolicyEndorsement> findAll(UUID tenantId, UUID policyId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ?
                 ORDER BY sequence ASC
                """, mapper(), tenantId, policyId);
    }

    public boolean numberExists(UUID tenantId, UUID policyId, String endorsementNumber) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM policy_endorsement
                 WHERE tenant_id = ? AND policy_id = ? AND endorsement_number = ?
                """, Long.class, tenantId, policyId, endorsementNumber);
        return count != null && count > 0;
    }

    public int nextSequence(UUID tenantId, UUID policyId) {
        Integer max = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence), 0) FROM policy_endorsement
                 WHERE tenant_id = ? AND policy_id = ?
                """, Integer.class, tenantId, policyId);
        return (max == null ? 0 : max) + 1;
    }

    private RowMapper<PolicyEndorsement> mapper() {
        return (rs, rowNum) -> new PolicyEndorsement(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("policy_id", UUID.class),
                rs.getObject("policy_version_id", UUID.class),
                rs.getString("endorsement_number"),
                rs.getInt("sequence"),
                rs.getString("form_number"),
                rs.getString("form_edition"),
                rs.getString("endorsement_type"),
                rs.getObject("effective_date", LocalDate.class),
                rs.getString("description"),
                rs.getBigDecimal("premium_delta_amount"),
                rs.getString("premium_delta_currency"),
                rs.getString("coverage_impact"),
                readChanges(rs.getString("coverage_changes")),
                rs.getBoolean("manuscript_flag"),
                rs.getString("backdate_reason"),
                rs.getObject("approval_ref", UUID.class),
                rs.getObject("approved_by", UUID.class),
                rs.getString("status"),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private String writeChanges(List<EndorsementCoverageChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(changes);
        } catch (Exception ex) {
            throw new IllegalStateException("Coverage changes could not be serialized", ex);
        }
    }

    private List<EndorsementCoverageChange> readChanges(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<EndorsementCoverageChange>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }
}
