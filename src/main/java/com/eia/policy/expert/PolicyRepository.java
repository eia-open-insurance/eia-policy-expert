package com.eia.policy.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PolicyRepository {
    private static final String SELECT_COLUMNS = """
            SELECT id, tenant_id, policy_number, product_code, insured_party_id, status,
                   effective_date, expiration_date, premium_amount, premium_currency,
                   limit_amount, limit_currency, version, created_at, updated_at
              FROM policy
            """;

    private static final RowMapper<Policy> MAPPER = (rs, rowNum) -> new Policy(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("policy_number"),
            rs.getString("product_code"),
            rs.getObject("insured_party_id", UUID.class),
            rs.getString("status"),
            rs.getObject("effective_date", LocalDate.class),
            rs.getObject("expiration_date", LocalDate.class),
            rs.getBigDecimal("premium_amount"),
            rs.getString("premium_currency"),
            rs.getBigDecimal("limit_amount"),
            rs.getString("limit_currency"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public PolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Policy> findAll(UUID tenantId) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ?
                 ORDER BY created_at DESC, policy_number
                """, MAPPER, tenantId);
    }

    public Optional<Policy> findById(UUID tenantId, UUID id) {
        return jdbc.query(SELECT_COLUMNS + """
                 WHERE tenant_id = ? AND id = ?
                """, MAPPER, tenantId, id).stream().findFirst();
    }

    public Policy create(CreatePolicyRequest request) {
        String status = PolicyRules.normalizeInitialStatus(request.status());
        String policyNumber = request.policyNumber() == null || request.policyNumber().isBlank()
                ? nextPolicyNumber()
                : request.policyNumber().trim();
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO policy (id, tenant_id, policy_number, product_code, insured_party_id, status,
                                    effective_date, expiration_date, premium_amount, premium_currency,
                                    limit_amount, limit_currency, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, id, request.tenantId(), policyNumber, request.productCode().trim(),
                request.insuredPartyId(), status, request.effectiveDate(), request.expirationDate(),
                request.premiumAmount(), request.premiumCurrency(), request.limitAmount(),
                request.limitCurrency(), Timestamp.from(now), Timestamp.from(now));
        return findById(request.tenantId(), id).orElseThrow();
    }

    public Policy updateStatus(UUID tenantId, UUID id, String status, String reason, int expectedVersion) {
        Instant now = Instant.now();
        int rows = jdbc.update("""
                UPDATE policy
                   SET status = ?, status_reason = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND id = ? AND version = ?
                """, status, blankToNull(reason), Timestamp.from(now), tenantId, id, expectedVersion);
        if (rows == 0) {
            boolean exists = findById(tenantId, id).isPresent();
            if (!exists) {
                throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "POLICY_NOT_FOUND", "Policy not found: " + id);
            }
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "CONCURRENT_MODIFICATION", "Policy was modified by another request; reload and retry");
        }
        return findById(tenantId, id).orElseThrow();
    }

    private String nextPolicyNumber() {
        Long sequence = jdbc.queryForObject("SELECT nextval('policy_number_seq')", Long.class);
        long value = sequence == null ? 1L : sequence;
        String year = String.valueOf(LocalDate.now(ZoneOffset.UTC).getYear());
        return "POL-" + year + "-" + String.format("%07d", value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
