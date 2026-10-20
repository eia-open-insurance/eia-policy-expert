package com.eia.policy.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for billing plans and their installment schedule. Every query is
 * tenant-scoped, and installment lookups join the plan so a cross-tenant or
 * cross-policy id can never be mutated.
 */
@Repository
public class BillingRepository {
    private static final String PLAN_COLUMNS = """
            SELECT id, tenant_id, policy_id, plan_number, total_premium, currency,
                   installment_count, frequency, first_due_date, status, created_at
              FROM policy_billing_plan
            """;

    private static final RowMapper<PolicyBillingPlan> PLAN_MAPPER = (rs, rowNum) ->
            new PolicyBillingPlan(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("policy_id", UUID.class),
                    rs.getString("plan_number"),
                    rs.getBigDecimal("total_premium"),
                    rs.getString("currency"),
                    rs.getInt("installment_count"),
                    rs.getString("frequency"),
                    rs.getObject("first_due_date", LocalDate.class),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant()
            );

    private static final String INSTALLMENT_COLUMNS = """
            SELECT id, tenant_id, plan_id, installment_number, due_date, amount, status,
                   paid_at, payment_id, created_at
              FROM policy_installment
            """;

    private static final RowMapper<PolicyInstallment> INSTALLMENT_MAPPER = (rs, rowNum) -> {
        Timestamp paidAt = rs.getTimestamp("paid_at");
        return new PolicyInstallment(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("plan_id", UUID.class),
                rs.getInt("installment_number"),
                rs.getObject("due_date", LocalDate.class),
                rs.getBigDecimal("amount"),
                rs.getString("status"),
                paidAt == null ? null : paidAt.toInstant(),
                rs.getObject("payment_id", UUID.class),
                rs.getTimestamp("created_at").toInstant()
        );
    };

    private static final RowMapper<ReceivableRow> RECEIVABLE_MAPPER = (rs, rowNum) -> {
        Timestamp paidAt = rs.getTimestamp("paid_at");
        return new ReceivableRow(
                rs.getObject("installment_id", UUID.class),
                rs.getObject("plan_id", UUID.class),
                rs.getString("plan_number"),
                rs.getObject("policy_id", UUID.class),
                rs.getString("policy_number"),
                rs.getObject("insured_party_id", UUID.class),
                rs.getInt("installment_number"),
                rs.getObject("due_date", LocalDate.class),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("status"),
                paidAt == null ? null : paidAt.toInstant(),
                rs.getObject("payment_id", UUID.class)
        );
    };

    private final JdbcTemplate jdbc;

    public BillingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPlan(PolicyBillingPlan plan) {
        jdbc.update("""
                INSERT INTO policy_billing_plan
                       (id, tenant_id, policy_id, plan_number, total_premium, currency,
                        installment_count, frequency, first_due_date, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, plan.id(), plan.tenantId(), plan.policyId(), plan.planNumber(),
                plan.totalPremium(), plan.currency(), plan.installmentCount(), plan.frequency(),
                plan.firstDueDate(), plan.status(), Timestamp.from(plan.createdAt()));
    }

    public void insertInstallment(PolicyInstallment installment) {
        jdbc.update("""
                INSERT INTO policy_installment
                       (id, tenant_id, plan_id, installment_number, due_date, amount, status,
                        paid_at, payment_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, installment.id(), installment.tenantId(), installment.planId(),
                installment.installmentNumber(), installment.dueDate(), installment.amount(),
                installment.status(),
                installment.paidAt() == null ? null : Timestamp.from(installment.paidAt()),
                installment.paymentId(), Timestamp.from(installment.createdAt()));
    }

    public Optional<PolicyBillingPlan> findActivePlan(UUID tenantId, UUID policyId) {
        return jdbc.query(PLAN_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ? AND status = 'ACTIVE'
                """, PLAN_MAPPER, tenantId, policyId).stream().findFirst();
    }

    /**
     * The current plan is the ACTIVE one; otherwise the most recently created
     * plan (COMPLETE/CANCELLED) is returned so GET stays useful after the plan
     * lifecycle closes.
     */
    public Optional<PolicyBillingPlan> findCurrentPlan(UUID tenantId, UUID policyId) {
        return jdbc.query(PLAN_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ?
                 ORDER BY CASE WHEN status = 'ACTIVE' THEN 0 ELSE 1 END,
                          created_at DESC, id ASC
                 LIMIT 1
                """, PLAN_MAPPER, tenantId, policyId).stream().findFirst();
    }

    public Optional<PolicyBillingPlan> findPlan(UUID tenantId, UUID policyId, UUID planId) {
        return jdbc.query(PLAN_COLUMNS + """
                 WHERE tenant_id = ? AND policy_id = ? AND id = ?
                """, PLAN_MAPPER, tenantId, policyId, planId).stream().findFirst();
    }

    public List<PolicyInstallment> findInstallments(UUID tenantId, UUID planId) {
        return jdbc.query(INSTALLMENT_COLUMNS + """
                 WHERE tenant_id = ? AND plan_id = ?
                 ORDER BY installment_number ASC
                """, INSTALLMENT_MAPPER, tenantId, planId);
    }

    /**
     * Tenant-wide outstanding installments for the receivables worklist. The
     * plan and policy joins carry the plan number, policy number, and insured
     * party; all three tenant columns are checked so a mismatched row can never
     * leak across tenants. The optional status filter is applied to the
     * effective status (a stored DUE row past its due date counts as OVERDUE),
     * and {@code dueBefore} is an inclusive cutoff.
     */
    public List<ReceivableRow> findReceivables(UUID tenantId, String status, LocalDate today,
                                               LocalDate dueBefore, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT i.id AS installment_id, p.id AS plan_id, p.plan_number,
                       pol.id AS policy_id, pol.policy_number, pol.insured_party_id,
                       i.installment_number, i.due_date, i.amount, p.currency,
                       i.status, i.paid_at, i.payment_id
                  FROM policy_installment i
                  JOIN policy_billing_plan p ON p.id = i.plan_id
                  JOIN policy pol ON pol.id = p.policy_id
                 WHERE i.tenant_id = ? AND p.tenant_id = ? AND pol.tenant_id = ?
                """);
        List<Object> args = new ArrayList<>(7);
        args.add(tenantId);
        args.add(tenantId);
        args.add(tenantId);
        if (status == null) {
            sql.append("   AND i.status IN ('DUE', 'OVERDUE')\n");
        } else if ("DUE".equals(status)) {
            sql.append("   AND i.status = ? AND i.due_date >= ?\n");
            args.add("DUE");
            args.add(today);
        } else {
            sql.append("   AND (i.status = ? OR (i.status = ? AND i.due_date < ?))\n");
            args.add("OVERDUE");
            args.add("DUE");
            args.add(today);
        }
        if (dueBefore != null) {
            sql.append("   AND i.due_date <= ?\n");
            args.add(dueBefore);
        }
        sql.append("""
                 ORDER BY i.due_date ASC, pol.policy_number ASC,
                          i.installment_number ASC, i.id ASC
                 LIMIT ?
                """);
        args.add(limit);
        return jdbc.query(sql.toString(), RECEIVABLE_MAPPER, args.toArray());
    }

    /**
     * Looks the installment up through its plan and policy, so the tenant and
     * policy wall are enforced by the query itself.
     */
    public Optional<PolicyInstallment> findInstallment(UUID tenantId, UUID policyId,
                                                       UUID installmentId) {
        return jdbc.query("""
                        SELECT i.id, i.tenant_id, i.plan_id, i.installment_number, i.due_date,
                               i.amount, i.status, i.paid_at, i.payment_id, i.created_at
                          FROM policy_installment i
                          JOIN policy_billing_plan p ON p.id = i.plan_id
                         WHERE i.tenant_id = ? AND p.tenant_id = ? AND p.policy_id = ?
                           AND i.id = ?
                        """, INSTALLMENT_MAPPER, tenantId, tenantId, policyId, installmentId)
                .stream().findFirst();
    }

    /**
     * Conditional transition: only a DUE/OVERDUE row still at the expected
     * status is settled. A concurrent winner makes this return zero.
     */
    public int updateStatus(UUID tenantId, UUID planId, UUID installmentId, String status,
                            Instant paidAt, UUID paymentId, String expectedStatus) {
        return jdbc.update("""
                UPDATE policy_installment
                   SET status = ?, paid_at = ?, payment_id = ?
                 WHERE tenant_id = ? AND plan_id = ? AND id = ? AND status = ?
                """, status,
                paidAt == null ? null : Timestamp.from(paidAt),
                paymentId, tenantId, planId, installmentId, expectedStatus);
    }

    public String nextPlanNumber(UUID tenantId, UUID policyId) {
        List<String> numbers = jdbc.queryForList("""
                SELECT plan_number
                  FROM policy_billing_plan
                 WHERE tenant_id = ? AND policy_id = ?
                """, String.class, tenantId, policyId);
        int max = 0;
        for (String number : numbers) {
            max = Math.max(max, planNumberSuffix(number));
        }
        return "BP-" + String.format("%04d", max + 1);
    }

    private static int planNumberSuffix(String planNumber) {
        if (planNumber == null || !planNumber.startsWith("BP-")) {
            return 0;
        }
        try {
            return Integer.parseInt(planNumber.substring(3));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
