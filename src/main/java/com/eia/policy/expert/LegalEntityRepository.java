package com.eia.policy.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the legal entity catalog. Every read is tenant-scoped, so a
 * legal entity id from another tenant is indistinguishable from a missing one.
 */
@Repository
public class LegalEntityRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, code, name, entity_type, jurisdiction, regulator,
                   license_number, status, created_at
              FROM legal_entity
            """;

    private static final RowMapper<LegalEntity> MAPPER = (rs, rowNum) -> new LegalEntity(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("entity_type"),
            rs.getString("jurisdiction"),
            rs.getString("regulator"),
            rs.getString("license_number"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public LegalEntityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LegalEntity> findById(UUID tenantId, UUID id) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND id = ?
                """, MAPPER, tenantId, id).stream().findFirst();
    }

    /**
     * Newest-first list, matching the program catalog order. The id tie-breaker
     * keeps the order deterministic when two rows share a timestamp.
     */
    public List<LegalEntity> findAll(UUID tenantId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ?
                 ORDER BY created_at DESC, id ASC
                """, MAPPER, tenantId);
    }

    public void insert(LegalEntity legalEntity) {
        jdbc.update("""
                INSERT INTO legal_entity
                       (id, tenant_id, code, name, entity_type, jurisdiction, regulator,
                        license_number, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, legalEntity.id(), legalEntity.tenantId(), legalEntity.code(),
                legalEntity.name(), legalEntity.entityType(), legalEntity.jurisdiction(),
                legalEntity.regulator(), legalEntity.licenseNumber(), legalEntity.status(),
                Timestamp.from(legalEntity.createdAt()));
    }
}
