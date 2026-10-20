package com.eia.policy.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the commercial program catalog. Every read is tenant-scoped,
 * so a program id from another tenant is indistinguishable from a missing one.
 */
@Repository
public class PolicyProgramRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, program_number, name, program_type, effective_from,
                   effective_to, status, lead_policy_id, created_at
              FROM policy_program
            """;

    private static final RowMapper<PolicyProgram> MAPPER = (rs, rowNum) -> new PolicyProgram(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("program_number"),
            rs.getString("name"),
            rs.getString("program_type"),
            rs.getObject("effective_from", LocalDate.class),
            rs.getObject("effective_to", LocalDate.class),
            rs.getString("status"),
            rs.getObject("lead_policy_id", UUID.class),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public PolicyProgramRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PolicyProgram> findById(UUID tenantId, UUID id) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND id = ?
                """, MAPPER, tenantId, id).stream().findFirst();
    }

    /**
     * Newest-first list, matching the policy list order. The id tie-breaker
     * keeps the order deterministic when two rows share a timestamp.
     */
    public List<PolicyProgram> findAll(UUID tenantId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ?
                 ORDER BY created_at DESC, id ASC
                """, MAPPER, tenantId);
    }

    public void insert(PolicyProgram program) {
        jdbc.update("""
                INSERT INTO policy_program
                       (id, tenant_id, program_number, name, program_type, effective_from,
                        effective_to, status, lead_policy_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, program.id(), program.tenantId(), program.programNumber(), program.name(),
                program.programType(), program.effectiveFrom(), program.effectiveTo(),
                program.status(), program.leadPolicyId(),
                Timestamp.from(program.createdAt()));
    }

    /**
     * Server-generated program number. The sequence makes concurrent creates
     * collision-free; {@code (tenant_id, program_number)} is the safety net.
     */
    public String nextProgramNumber() {
        Long sequence = jdbc.queryForObject(
                "SELECT nextval('policy_program_number_seq')", Long.class);
        long value = sequence == null ? 1L : sequence;
        String year = String.valueOf(LocalDate.now(ZoneOffset.UTC).getYear());
        return "PRG-" + year + "-" + String.format("%07d", value);
    }
}
