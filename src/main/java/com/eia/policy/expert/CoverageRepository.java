package com.eia.policy.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CoverageRepository {
    private static final RowMapper<PolicyCoverage> COVERAGE_MAPPER = (rs, rowNum) -> new PolicyCoverage(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("policy_id", UUID.class),
            rs.getString("coverage_code"),
            rs.getString("coverage_name"),
            rs.getString("coverage_part"),
            rs.getString("occurrence_basis"),
            rs.getBigDecimal("coinsurance_pct"),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class),
            rs.getString("status"),
            rs.getInt("sequence"),
            rs.getTimestamp("created_at").toInstant()
    );

    private static final String COVERAGE_COLUMNS = """
            SELECT id, tenant_id, policy_id, coverage_code, coverage_name, coverage_part,
                   occurrence_basis, coinsurance_pct, effective_from, effective_to, status,
                   sequence, created_at
              FROM policy_coverage
            """;

    private static final RowMapper<PolicyLimit> LIMIT_MAPPER = (rs, rowNum) -> new PolicyLimit(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("policy_id", UUID.class),
            rs.getObject("coverage_id", UUID.class),
            rs.getBigDecimal("per_occurrence_amount"),
            rs.getBigDecimal("aggregate_amount"),
            rs.getString("aggregate_basis"),
            rs.getBigDecimal("sublimit_amount"),
            rs.getBigDecimal("deductible_amount"),
            rs.getBigDecimal("coinsurance_pct"),
            rs.getString("currency"),
            rs.getString("erosion_basis"),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class),
            rs.getInt("sequence"),
            rs.getTimestamp("created_at").toInstant()
    );

    private static final String LIMIT_COLUMNS = """
            SELECT id, tenant_id, policy_id, coverage_id, per_occurrence_amount, aggregate_amount,
                   aggregate_basis, sublimit_amount, deductible_amount, coinsurance_pct, currency,
                   erosion_basis, effective_from, effective_to, sequence, created_at
              FROM policy_limit
            """;

    private static final RowMapper<PolicyLimitState> STATE_MAPPER = (rs, rowNum) -> new PolicyLimitState(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("policy_id", UUID.class),
            rs.getObject("limit_id", UUID.class),
            rs.getTimestamp("as_of").toInstant(),
            rs.getBigDecimal("original_amount"),
            rs.getBigDecimal("paid_amount"),
            rs.getBigDecimal("reserved_amount"),
            rs.getBigDecimal("incurred_amount"),
            rs.getBigDecimal("recovered_amount"),
            rs.getBigDecimal("remaining_amount"),
            rs.getBigDecimal("eroded_pct"),
            rs.getBigDecimal("reinstated_amount"),
            rs.getLong("source_version"),
            rs.getTimestamp("computed_at").toInstant(),
            rs.getString("reconciliation_status"),
            rs.getInt("version")
    );

    private static final String STATE_COLUMNS = """
            SELECT id, tenant_id, policy_id, limit_id, as_of, original_amount, paid_amount,
                   reserved_amount, incurred_amount, recovered_amount, remaining_amount,
                   eroded_pct, reinstated_amount, source_version, computed_at,
                   reconciliation_status, version
              FROM policy_limit_state
            """;

    private final JdbcTemplate jdbc;

    public CoverageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertCoverage(PolicyCoverage coverage) {
        jdbc.update("""
                INSERT INTO policy_coverage
                       (id, tenant_id, policy_id, coverage_code, coverage_name, coverage_part,
                        occurrence_basis, coinsurance_pct, effective_from, effective_to, status,
                        sequence, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, coverage.id(), coverage.tenantId(), coverage.policyId(),
                coverage.coverageCode(), coverage.coverageName(), coverage.coveragePart(),
                coverage.occurrenceBasis(), coverage.coinsurancePct(), coverage.effectiveFrom(),
                coverage.effectiveTo(), coverage.status(), coverage.sequence(),
                Timestamp.from(coverage.createdAt()));
    }

    public void insertLimit(PolicyLimit limit) {
        jdbc.update("""
                INSERT INTO policy_limit
                       (id, tenant_id, policy_id, coverage_id, per_occurrence_amount,
                        aggregate_amount, aggregate_basis, sublimit_amount, deductible_amount,
                        coinsurance_pct, currency, erosion_basis, effective_from, effective_to,
                        sequence, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, limit.id(), limit.tenantId(), limit.policyId(), limit.coverageId(),
                limit.perOccurrenceAmount(), limit.aggregateAmount(), limit.aggregateBasis(),
                limit.sublimitAmount(), limit.deductibleAmount(), limit.coinsurancePct(),
                limit.currency(), limit.erosionBasis(), limit.effectiveFrom(), limit.effectiveTo(),
                limit.sequence(), Timestamp.from(limit.createdAt()));
    }

    public void insertState(PolicyLimitState state) {
        jdbc.update("""
                INSERT INTO policy_limit_state
                       (id, tenant_id, policy_id, limit_id, as_of, original_amount, paid_amount,
                        reserved_amount, incurred_amount, recovered_amount, remaining_amount,
                        eroded_pct, reinstated_amount, source_version, computed_at,
                        reconciliation_status, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, state.id(), state.tenantId(), state.policyId(), state.limitId(),
                Timestamp.from(state.asOf()), state.originalAmount(), state.paidAmount(),
                state.reservedAmount(), state.incurredAmount(), state.recoveredAmount(),
                state.remainingAmount(), state.erodedPct(), state.reinstatedAmount(),
                state.sourceVersion(), Timestamp.from(state.computedAt()),
                state.reconciliationStatus(), state.version());
    }

    public Optional<PolicyCoverage> findCoverage(UUID tenantId, UUID policyId, UUID coverageId) {
        return jdbc.query(COVERAGE_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ? AND id = ?
                """, COVERAGE_MAPPER, tenantId, policyId, coverageId).stream().findFirst();
    }

    public boolean coverageCodeExists(UUID tenantId, UUID policyId, String coverageCode) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM policy_coverage
                 WHERE tenant_id = ? AND policy_id = ? AND coverage_code = ? AND status = 'ACTIVE'
                """, Long.class, tenantId, policyId, coverageCode);
        return count != null && count > 0;
    }

    public List<PolicyCoverage> findCoverages(UUID tenantId, UUID policyId) {
        return jdbc.query(COVERAGE_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ?
                 ORDER BY sequence ASC
                """, COVERAGE_MAPPER, tenantId, policyId);
    }

    public int nextCoverageSequence(UUID tenantId, UUID policyId) {
        Integer max = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence), 0) FROM policy_coverage
                 WHERE tenant_id = ? AND policy_id = ?
                """, Integer.class, tenantId, policyId);
        return (max == null ? 0 : max) + 1;
    }

    public Optional<PolicyLimit> findLimitByCoverage(UUID tenantId, UUID policyId, UUID coverageId) {
        return jdbc.query(LIMIT_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ? AND coverage_id = ?
                """, LIMIT_MAPPER, tenantId, policyId, coverageId).stream().findFirst();
    }

    public Optional<PolicyLimit> findLimit(UUID tenantId, UUID policyId, UUID limitId) {
        return jdbc.query(LIMIT_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ? AND id = ?
                """, LIMIT_MAPPER, tenantId, policyId, limitId).stream().findFirst();
    }

    public List<PolicyLimit> findLimits(UUID tenantId, UUID policyId) {
        return jdbc.query(LIMIT_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ?
                 ORDER BY sequence ASC
                """, LIMIT_MAPPER, tenantId, policyId);
    }

    public Optional<PolicyLimitState> findState(UUID tenantId, UUID policyId, UUID limitId) {
        return jdbc.query(STATE_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ? AND limit_id = ?
                """, STATE_MAPPER, tenantId, policyId, limitId).stream().findFirst();
    }

    public List<PolicyLimitState> findStates(UUID tenantId, UUID policyId) {
        return jdbc.query(STATE_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ?
                 ORDER BY computed_at ASC
                """, STATE_MAPPER, tenantId, policyId);
    }

    public int updateState(PolicyLimitState state, int expectedVersion) {
        return jdbc.update("""
                UPDATE policy_limit_state
                   SET as_of = ?, paid_amount = ?, reserved_amount = ?, incurred_amount = ?,
                       recovered_amount = ?, remaining_amount = ?, eroded_pct = ?,
                       reinstated_amount = ?, source_version = ?, computed_at = ?,
                       reconciliation_status = ?, version = version + 1
                 WHERE tenant_id = ? AND policy_id = ? AND limit_id = ? AND version = ?
                """, Timestamp.from(state.asOf()), state.paidAmount(), state.reservedAmount(),
                state.incurredAmount(), state.recoveredAmount(), state.remainingAmount(),
                state.erodedPct(), state.reinstatedAmount(), state.sourceVersion(),
                Timestamp.from(state.computedAt()), state.reconciliationStatus(),
                state.tenantId(), state.policyId(), state.limitId(), expectedVersion);
    }

    public boolean erosionFactExists(UUID limitId, UUID claimId, UUID allocationId,
                                     long sourceVersion) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM policy_limit_erosion
                 WHERE limit_id = ? AND claim_id = ? AND allocation_id = ? AND source_version = ?
                """, Long.class, limitId, claimId, allocationId, sourceVersion);
        return count != null && count > 0;
    }

    public boolean chainHasNewerFact(UUID limitId, UUID claimId, UUID allocationId,
                                     long sourceVersion) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM policy_limit_erosion
                 WHERE limit_id = ? AND claim_id = ? AND allocation_id = ? AND source_version > ?
                """, Long.class, limitId, claimId, allocationId, sourceVersion);
        return count != null && count > 0;
    }

    public void insertErosionFact(UUID id, UUID tenantId, UUID policyId, UUID limitId, UUID claimId,
                                  UUID allocationId, long sourceVersion, BigDecimal paid,
                                  BigDecimal reserved, BigDecimal recovered, Instant asOf,
                                  Instant createdAt) {
        jdbc.update("""
                INSERT INTO policy_limit_erosion
                       (id, tenant_id, policy_id, limit_id, claim_id, allocation_id, source_version,
                        paid_amount, reserved_amount, recovered_amount, as_of, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, policyId, limitId, claimId, allocationId, sourceVersion,
                paid, reserved, recovered, Timestamp.from(asOf), Timestamp.from(createdAt));
    }

    /** Totals across the latest fact of each claim/allocation chain. */
    public ErosionTotals sumLatestFacts(UUID tenantId, UUID policyId, UUID limitId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(paid_amount), 0) AS paid,
                       COALESCE(SUM(reserved_amount), 0) AS reserved,
                       COALESCE(SUM(recovered_amount), 0) AS recovered,
                       COALESCE(MAX(source_version), 0) AS max_source_version
                  FROM (
                        SELECT DISTINCT ON (claim_id, allocation_id)
                               paid_amount, reserved_amount, recovered_amount, source_version
                          FROM policy_limit_erosion
                         WHERE tenant_id = ? AND policy_id = ? AND limit_id = ?
                         ORDER BY claim_id, allocation_id, source_version DESC
                       ) latest
                """, (rs, rowNum) -> new ErosionTotals(
                rs.getBigDecimal("paid"),
                rs.getBigDecimal("reserved"),
                rs.getBigDecimal("recovered"),
                rs.getLong("max_source_version")),
                tenantId, policyId, limitId);
    }

    /** Aggregated erosion totals across the latest fact per chain. */
    public record ErosionTotals(BigDecimal paid, BigDecimal reserved, BigDecimal recovered,
                                long maxSourceVersion) {
    }
}
