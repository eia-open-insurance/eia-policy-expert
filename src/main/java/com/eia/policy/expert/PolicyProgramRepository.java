package com.eia.policy.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Persistence for the commercial program catalog. Every read is tenant-scoped,
 * so a program id from another tenant is indistinguishable from a missing one.
 *
 * <p>The capacity usage read model counts the tenant's policies assigned to a
 * program and sums their {@code limit_amount}; the aggregate is derived from
 * the policy facts on every read rather than stored on the program row.</p>
 */
@Repository
public class PolicyProgramRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, program_number, name, program_type, effective_from,
                   effective_to, status, capacity_amount, capacity_currency, lead_policy_id,
                   created_at
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
            rs.getBigDecimal("capacity_amount"),
            rs.getString("capacity_currency"),
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
                        effective_to, status, capacity_amount, capacity_currency, lead_policy_id,
                        created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, program.id(), program.tenantId(), program.programNumber(), program.name(),
                program.programType(), program.effectiveFrom(), program.effectiveTo(),
                program.status(), program.capacityAmount(), program.capacityCurrency(),
                program.leadPolicyId(), Timestamp.from(program.createdAt()));
    }

    /**
     * Number of policies assigned to one program and the sum of their limits.
     * The aggregate always returns one row, so a program with no policies
     * reports a zero count and a zero amount.
     */
    public ProgramUsage findUsage(UUID tenantId, UUID programId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS policy_count, COALESCE(SUM(limit_amount), 0) AS used_amount
                  FROM policy
                 WHERE tenant_id = ? AND program_id = ?
                """, (rs, rowNum) -> new ProgramUsage(rs.getLong("policy_count"),
                        rs.getBigDecimal("used_amount")), tenantId, programId);
    }

    /**
     * Usage for every program in the tenant in one grouped query, keyed by
     * program id. Programs without policies are simply absent from the map.
     */
    public Map<UUID, ProgramUsage> findUsageByProgram(UUID tenantId) {
        return jdbc.query("""
                SELECT program_id, COUNT(*) AS policy_count, SUM(limit_amount) AS used_amount
                  FROM policy
                 WHERE tenant_id = ? AND program_id IS NOT NULL
                 GROUP BY program_id
                """, (rs, rowNum) -> Map.entry(
                        rs.getObject("program_id", UUID.class),
                        new ProgramUsage(rs.getLong("policy_count"),
                                rs.getBigDecimal("used_amount"))),
                tenantId).stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
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
