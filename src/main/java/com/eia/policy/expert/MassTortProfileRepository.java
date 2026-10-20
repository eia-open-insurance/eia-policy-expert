package com.eia.policy.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the one-per-policy mass tort profile. Every query is
 * tenant-scoped, so a cross-tenant policy id can never read or mutate a row.
 */
@Repository
public class MassTortProfileRepository {
    private static final String PROFILE_COLUMNS = """
            SELECT id, tenant_id, policy_id, tort_type, trigger_type, allocation_method,
                   allocation_notes, coverage_block_start, coverage_block_end, status, updated_at
              FROM policy_mass_tort_profile
            """;

    private static final RowMapper<PolicyMassTortProfile> PROFILE_MAPPER = (rs, rowNum) ->
            new PolicyMassTortProfile(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("policy_id", UUID.class),
                    rs.getString("tort_type"),
                    rs.getString("trigger_type"),
                    rs.getString("allocation_method"),
                    rs.getString("allocation_notes"),
                    rs.getObject("coverage_block_start", LocalDate.class),
                    rs.getObject("coverage_block_end", LocalDate.class),
                    rs.getString("status"),
                    rs.getTimestamp("updated_at").toInstant()
            );

    private final JdbcTemplate jdbc;

    public MassTortProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PolicyMassTortProfile> find(UUID tenantId, UUID policyId) {
        return jdbc.query(PROFILE_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ?
                """, PROFILE_MAPPER, tenantId, policyId).stream().findFirst();
    }

    public void insert(PolicyMassTortProfile profile) {
        jdbc.update("""
                INSERT INTO policy_mass_tort_profile
                       (id, tenant_id, policy_id, tort_type, trigger_type, allocation_method,
                        allocation_notes, coverage_block_start, coverage_block_end, status,
                        updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, profile.id(), profile.tenantId(), profile.policyId(),
                profile.tortType(), profile.triggerType(), profile.allocationMethod(),
                profile.allocationNotes(), profile.coverageBlockStart(),
                profile.coverageBlockEnd(), profile.status(),
                Timestamp.from(profile.updatedAt()));
    }

    /**
     * Rewrites the profile in place, preserving its id. The policy and tenant
     * predicates make the update a no-op for a foreign row.
     */
    public int update(PolicyMassTortProfile profile) {
        return jdbc.update("""
                UPDATE policy_mass_tort_profile
                   SET tort_type = ?, trigger_type = ?, allocation_method = ?,
                       allocation_notes = ?, coverage_block_start = ?, coverage_block_end = ?,
                       status = ?, updated_at = ?
                 WHERE tenant_id = ? AND policy_id = ? AND id = ?
                """, profile.tortType(), profile.triggerType(), profile.allocationMethod(),
                profile.allocationNotes(), profile.coverageBlockStart(),
                profile.coverageBlockEnd(), profile.status(),
                Timestamp.from(profile.updatedAt()), profile.tenantId(), profile.policyId(),
                profile.id());
    }
}
