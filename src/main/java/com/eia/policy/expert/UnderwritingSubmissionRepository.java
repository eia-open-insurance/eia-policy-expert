package com.eia.policy.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for underwriting submissions. Every read is tenant-scoped, so a
 * submission id from another tenant is indistinguishable from a missing one.
 *
 * <p>The status update is conditional on both the expected version and the
 * expected current status; the caller treats zero affected rows as a concurrent
 * change. {@code tort_codes} is a PostgreSQL {@code text[]}; the JDBC array is
 * created on the transaction-bound connection so the driver owns the type
 * mapping.</p>
 */
@Repository
public class UnderwritingSubmissionRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, submission_number, applicant_party_id, product_code,
                   requested_effective_date, requested_limit_amount,
                   requested_limit_currency, tort_codes, status, notes, version,
                   created_at, updated_at
              FROM underwriting_submission
            """;

    private static final RowMapper<UnderwritingSubmission> MAPPER =
            (rs, rowNum) -> new UnderwritingSubmission(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getString("submission_number"),
                    rs.getObject("applicant_party_id", UUID.class),
                    rs.getString("product_code"),
                    rs.getObject("requested_effective_date", LocalDate.class),
                    rs.getBigDecimal("requested_limit_amount"),
                    rs.getString("requested_limit_currency"),
                    tortCodes(rs),
                    rs.getString("status"),
                    rs.getString("notes"),
                    rs.getInt("version"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public UnderwritingSubmissionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UnderwritingSubmission> findById(UUID tenantId, UUID id) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND id = ?
                """, MAPPER, tenantId, id).stream().findFirst();
    }

    /**
     * Tenant-scoped list with the optional free-text and status filters. The
     * free-text match is case-insensitive across the submission number, product
     * code, and notes. Ordering is stable: newest update first, id tie-breaker.
     */
    public List<UnderwritingSubmission> findAll(UUID tenantId, String q, String status) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS)
                .append(" WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (q != null) {
            sql.append("""
                     AND (LOWER(submission_number) LIKE ?
                       OR LOWER(product_code) LIKE ?
                       OR LOWER(COALESCE(notes, '')) LIKE ?)
                    """);
            String pattern = "%" + q.toLowerCase(Locale.ROOT) + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        sql.append(" ORDER BY updated_at DESC, id ASC");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public void insert(UnderwritingSubmission submission) {
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO underwriting_submission
                           (id, tenant_id, submission_number, applicant_party_id, product_code,
                            requested_effective_date, requested_limit_amount,
                            requested_limit_currency, tort_codes, status, notes, version,
                            created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            ps.setObject(1, submission.id());
            ps.setObject(2, submission.tenantId());
            ps.setString(3, submission.submissionNumber());
            ps.setObject(4, submission.applicantPartyId());
            ps.setString(5, submission.productCode());
            ps.setObject(6, submission.requestedEffectiveDate());
            ps.setBigDecimal(7, submission.requestedLimitAmount());
            ps.setString(8, submission.requestedLimitCurrency());
            ps.setArray(9, connection.createArrayOf("text",
                    submission.tortCodes().toArray()));
            ps.setString(10, submission.status());
            ps.setString(11, submission.notes());
            ps.setInt(12, submission.version());
            ps.setTimestamp(13, Timestamp.from(submission.createdAt()));
            ps.setTimestamp(14, Timestamp.from(submission.updatedAt()));
            return ps;
        });
    }

    /**
     * Advances the status once. The version and the expected current status are
     * both part of the predicate, so a concurrent transition (or a stale
     * version) affects no row instead of overwriting the winner.
     */
    public int updateStatus(UUID tenantId, UUID id, String targetStatus,
                            String expectedStatus, int expectedVersion, Instant updatedAt) {
        return jdbc.update("""
                UPDATE underwriting_submission
                   SET status = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ? AND status = ?
                """, targetStatus, Timestamp.from(updatedAt), tenantId, id, expectedVersion,
                expectedStatus);
    }

    private static List<String> tortCodes(ResultSet rs) throws SQLException {
        Array array = rs.getArray("tort_codes");
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] values) {
            return List.of(values);
        }
        if (raw instanceof Object[] values) {
            List<String> codes = new ArrayList<>(values.length);
            for (Object value : values) {
                codes.add(value == null ? null : value.toString());
            }
            return List.copyOf(codes);
        }
        return List.of();
    }
}
