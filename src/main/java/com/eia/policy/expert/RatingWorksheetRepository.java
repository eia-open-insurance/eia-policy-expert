package com.eia.policy.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * Append-only policy rating worksheets. Reads are always tenant scoped, so a
 * worksheet can never leak across the tenant wall.
 */
@Repository
public class RatingWorksheetRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, policy_id, rating_method, base_rate, exposure_amount,
                   factor, computed_premium, currency, calc_hash, status, created_at,
                   created_by
              FROM policy_rating_worksheet
            """;

    private static final RowMapper<PolicyRatingWorksheet> MAPPER = (rs, rowNum) ->
            new PolicyRatingWorksheet(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("policy_id", UUID.class),
                    rs.getString("rating_method"),
                    rs.getBigDecimal("base_rate"),
                    rs.getBigDecimal("exposure_amount"),
                    rs.getBigDecimal("factor"),
                    rs.getBigDecimal("computed_premium"),
                    rs.getString("currency"),
                    rs.getString("calc_hash"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getObject("created_by", UUID.class));

    private final JdbcTemplate jdbc;

    public RatingWorksheetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(PolicyRatingWorksheet worksheet) {
        jdbc.update("""
                INSERT INTO policy_rating_worksheet
                       (id, tenant_id, policy_id, rating_method, base_rate, exposure_amount,
                        factor, computed_premium, currency, calc_hash, status, created_at,
                        created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                worksheet.id(), worksheet.tenantId(), worksheet.policyId(),
                worksheet.ratingMethod(), worksheet.baseRate(), worksheet.exposureAmount(),
                worksheet.factor(), worksheet.computedPremium(), worksheet.currency(),
                worksheet.calcHash(), worksheet.status(),
                Timestamp.from(worksheet.createdAt()), worksheet.createdBy());
    }

    /** The newest worksheet for a policy, ordered by calculation time. */
    public Optional<PolicyRatingWorksheet> findLatest(UUID tenantId, UUID policyId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT 1
                """, MAPPER, tenantId, policyId).stream().findFirst();
    }

    public Optional<PolicyRatingWorksheet> findById(UUID tenantId, UUID policyId, UUID id) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ? AND id = ?
                """, MAPPER, tenantId, policyId, id).stream().findFirst();
    }
}
