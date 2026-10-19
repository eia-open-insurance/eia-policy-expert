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
public class PolicyPartyRepository {
    private static final String INSURED_COLUMNS = """
            SELECT id, tenant_id, policy_id, party_id, insured_role, display_name,
                   certificate_number, scope, effective_from, effective_to, status, created_at
              FROM policy_insured
            """;

    private static final RowMapper<PolicyInsured> INSURED_MAPPER = (rs, rowNum) -> new PolicyInsured(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("policy_id", UUID.class),
            rs.getObject("party_id", UUID.class),
            rs.getString("insured_role"),
            rs.getString("display_name"),
            rs.getString("certificate_number"),
            rs.getString("scope"),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant()
    );

    private static final String LOCATION_COLUMNS = """
            SELECT id, tenant_id, policy_id, location_code, address_line1, address_line2, city,
                   state_region, postal_code, country, effective_from, effective_to, status,
                   created_at
              FROM policy_location
            """;

    private static final RowMapper<PolicyLocation> LOCATION_MAPPER = (rs, rowNum) -> new PolicyLocation(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getObject("policy_id", UUID.class),
            rs.getString("location_code"),
            rs.getString("address_line1"),
            rs.getString("address_line2"),
            rs.getString("city"),
            rs.getString("state_region"),
            rs.getString("postal_code"),
            rs.getString("country"),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public PolicyPartyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertInsured(PolicyInsured insured) {
        jdbc.update("""
                INSERT INTO policy_insured
                       (id, tenant_id, policy_id, party_id, insured_role, display_name,
                        certificate_number, scope, effective_from, effective_to, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, insured.id(), insured.tenantId(), insured.policyId(), insured.partyId(),
                insured.insuredRole(), insured.displayName(), insured.certificateNumber(),
                insured.scope(), insured.effectiveFrom(), insured.effectiveTo(),
                insured.status(), Timestamp.from(insured.createdAt()));
    }

    public Optional<PolicyInsured> findInsured(UUID tenantId, UUID policyId, UUID insuredId) {
        return jdbc.query(INSURED_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ? AND id = ?
                """, INSURED_MAPPER, tenantId, policyId, insuredId).stream().findFirst();
    }

    public List<PolicyInsured> findInsureds(UUID tenantId, UUID policyId) {
        return jdbc.query(INSURED_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ?
                 ORDER BY created_at ASC
                """, INSURED_MAPPER, tenantId, policyId);
    }

    public boolean insuredExists(UUID tenantId, UUID policyId, UUID partyId, String insuredRole) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM policy_insured
                 WHERE tenant_id = ? AND policy_id = ? AND party_id = ? AND insured_role = ?
                """, Long.class, tenantId, policyId, partyId, insuredRole);
        return count != null && count > 0;
    }

    public int countNamedInsured(UUID tenantId, UUID policyId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM policy_insured
                 WHERE tenant_id = ? AND policy_id = ? AND insured_role = 'NAMED_INSURED'
                   AND status = 'ACTIVE'
                """, Integer.class, tenantId, policyId);
        return count == null ? 0 : count;
    }

    public void insertLocation(PolicyLocation location) {
        jdbc.update("""
                INSERT INTO policy_location
                       (id, tenant_id, policy_id, location_code, address_line1, address_line2, city,
                        state_region, postal_code, country, effective_from, effective_to, status,
                        created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, location.id(), location.tenantId(), location.policyId(),
                location.locationCode(), location.addressLine1(), location.addressLine2(),
                location.city(), location.stateRegion(), location.postalCode(), location.country(),
                location.effectiveFrom(), location.effectiveTo(), location.status(),
                Timestamp.from(location.createdAt()));
    }

    public Optional<PolicyLocation> findLocation(UUID tenantId, UUID policyId, UUID locationId) {
        return jdbc.query(LOCATION_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ? AND id = ?
                """, LOCATION_MAPPER, tenantId, policyId, locationId).stream().findFirst();
    }

    public List<PolicyLocation> findLocations(UUID tenantId, UUID policyId) {
        return jdbc.query(LOCATION_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ?
                 ORDER BY created_at ASC
                """, LOCATION_MAPPER, tenantId, policyId);
    }

    public boolean locationCodeExists(UUID tenantId, UUID policyId, String locationCode) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM policy_location
                 WHERE tenant_id = ? AND policy_id = ? AND location_code = ?
                """, Long.class, tenantId, policyId, locationCode);
        return count != null && count > 0;
    }
}
