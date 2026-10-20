package com.eia.policy.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for installment dunning actions. Every query is tenant-scoped.
 * The installment, plan, and policy join in {@link #findTarget} checks all
 * three tenant columns, so a cross-tenant installment id is indistinguishable
 * from a missing one.
 */
@Repository
public class BillingDunningRepository {
    private static final String ACTION_COLUMNS = """
            SELECT id, tenant_id, installment_id, plan_id, policy_id, level, channel,
                   notes, status, sent_at, created_at
              FROM billing_dunning_action
            """;

    private static final RowMapper<BillingDunningAction> ACTION_MAPPER = (rs, rowNum) ->
            new BillingDunningAction(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("installment_id", UUID.class),
                    rs.getObject("plan_id", UUID.class),
                    rs.getObject("policy_id", UUID.class),
                    rs.getString("level"),
                    rs.getString("channel"),
                    rs.getString("notes"),
                    rs.getString("status"),
                    rs.getTimestamp("sent_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant()
            );

    private static final RowMapper<BillingDunningTarget> TARGET_MAPPER = (rs, rowNum) ->
            new BillingDunningTarget(
                    rs.getObject("installment_id", UUID.class),
                    rs.getObject("plan_id", UUID.class),
                    rs.getObject("policy_id", UUID.class),
                    rs.getString("stored_status"),
                    rs.getObject("due_date", LocalDate.class)
            );

    private final JdbcTemplate jdbc;

    public BillingDunningRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Resolves the dunning target through the installment, billing plan, and
     * policy. The three tenant predicates make the lookup tenant-safe even if
     * the denormalized tenant columns disagree.
     */
    public Optional<BillingDunningTarget> findTarget(UUID tenantId, UUID installmentId) {
        return jdbc.query("""
                        SELECT i.id AS installment_id, i.plan_id, i.due_date,
                               i.status AS stored_status, p.policy_id
                          FROM policy_installment i
                          JOIN policy_billing_plan p ON p.id = i.plan_id
                          JOIN policy pol ON pol.id = p.policy_id
                         WHERE i.tenant_id = ? AND p.tenant_id = ? AND pol.tenant_id = ?
                           AND i.id = ?
                        """, TARGET_MAPPER, tenantId, tenantId, tenantId, installmentId)
                .stream().findFirst();
    }

    /**
     * Newest-first dunning history for one installment. {@code sent_at} is the
     * primary order; {@code created_at} and {@code id} break ties so the list
     * is stable when actions are recorded at the same instant.
     */
    public List<BillingDunningAction> findForInstallment(UUID tenantId, UUID installmentId) {
        return jdbc.query(ACTION_COLUMNS + """
                 WHERE tenant_id = ? AND installment_id = ?
                 ORDER BY sent_at DESC, created_at DESC, id ASC
                """, ACTION_MAPPER, tenantId, installmentId);
    }

    /**
     * Looks up an existing action at the same escalation level. Any status
     * counts, mirroring the unique constraint: a cancelled level still occupies
     * the slot and cannot be sent again.
     */
    public Optional<BillingDunningAction> findByLevel(UUID tenantId, UUID installmentId,
                                                      String level) {
        return jdbc.query(ACTION_COLUMNS + """
                 WHERE tenant_id = ? AND installment_id = ? AND level = ?
                """, ACTION_MAPPER, tenantId, installmentId, level).stream().findFirst();
    }

    public void insert(BillingDunningAction action) {
        jdbc.update("""
                INSERT INTO billing_dunning_action
                       (id, tenant_id, installment_id, plan_id, policy_id, level, channel,
                        notes, status, sent_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, action.id(), action.tenantId(), action.installmentId(), action.planId(),
                action.policyId(), action.level(), action.channel(), action.notes(),
                action.status(), Timestamp.from(action.sentAt()),
                Timestamp.from(action.createdAt()));
    }
}
