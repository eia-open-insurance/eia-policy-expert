package com.eia.policy.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Per-tenant submission-number allocation.
 *
 * <p>The counter row is locked with {@code SELECT ... FOR UPDATE} and advanced
 * in its own short transaction, so two creates for the same tenant cannot read
 * the same value. A first-ever create for a tenant may race on the primary-key
 * insert; the losing transaction rolls back cleanly and the service retries,
 * after which the committed counter row is visible. The counter is deliberately
 * independent of the submission insert: a failed create consumes a number, the
 * same gap behaviour as a database sequence.</p>
 */
@Component
public class UnderwritingSubmissionNumberAllocator {
    private final JdbcTemplate jdbc;

    public UnderwritingSubmissionNumberAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long next(UUID tenantId) {
        List<Long> current = jdbc.query("""
                SELECT next_value
                  FROM underwriting_submission_number_seq
                 WHERE tenant_id = ?
                   FOR UPDATE
                """, (rs, rowNum) -> rs.getLong(1), tenantId);
        if (current.isEmpty()) {
            jdbc.update("""
                    INSERT INTO underwriting_submission_number_seq (tenant_id, next_value)
                    VALUES (?, 1)
                    """, tenantId);
            return 1L;
        }
        long next = current.get(0) + 1L;
        jdbc.update("""
                UPDATE underwriting_submission_number_seq
                   SET next_value = ?
                 WHERE tenant_id = ?
                """, next, tenantId);
        return next;
    }
}
