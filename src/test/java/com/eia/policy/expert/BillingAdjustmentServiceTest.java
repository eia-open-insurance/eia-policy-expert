package com.eia.policy.expert;

import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end positive premium adjustment against H2: appending the derived
 * remainder installment, the net-plan-premium comparison after applied credits,
 * plan total/count movement, zero/negative preconditions, missing and inactive
 * plans, the 24-installment ceiling, tenant isolation, the outbox payload, and
 * transactional rollback.
 */
@SpringBootTest
@ActiveProfiles("test")
class BillingAdjustmentServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);
    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 2, 1);
    private static final LocalDate ADJUSTMENT_DUE = LocalDate.of(2026, 5, 1);

    @Autowired
    private BillingService billingService;

    @Autowired
    private CreditNoteService creditNoteService;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        for (String table : List.of(
                "outbox_event", "policy_credit_note", "policy_installment",
                "policy_billing_plan",
                "policy_limit_erosion", "policy_limit_state", "policy_limit",
                "policy_coverage", "policy_endorsement", "policy_insured", "policy_location",
                "policy_cancellation", "policy_reinstatement", "policy_version",
                "policy_command_idempotency", "policy_idempotency", "policy")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void appendsThePositiveRemainderAndMovesThePlanTotal() {
        Policy policy = createPolicy("POL-ADJ-0001", new BigDecimal("120000.00"));
        BillingPlanView plan = billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(3, "MONTHLY", FIRST_DUE));
        raisePolicyPremium(policy.id(), new BigDecimal("150000.00"));

        BillingAdjustmentView view = billingService.adjust(TENANT, policy.id(),
                new BillingAdjustmentRequest(ADJUSTMENT_DUE, "  exposure audit  "));

        assertThat(view.adjustmentAmount()).isEqualByComparingTo("30000.0000");
        assertThat(view.newTotalPremium()).isEqualByComparingTo("150000.0000");
        assertThat(view.plan().id()).isEqualTo(plan.plan().id());
        assertThat(view.plan().totalPremium()).isEqualByComparingTo("150000.0000");
        assertThat(view.plan().installmentCount()).isEqualTo(4);
        assertThat(view.plan().status()).isEqualTo("ACTIVE");
        assertThat(view.plan().currency()).isEqualTo("USD");

        PolicyInstallment installment = view.installment();
        assertThat(installment.installmentNumber()).isEqualTo(4);
        assertThat(installment.dueDate()).isEqualTo(ADJUSTMENT_DUE);
        assertThat(installment.amount()).isEqualByComparingTo("30000.0000");
        assertThat(installment.creditedAmount()).isEqualByComparingTo("0.0000");
        assertThat(installment.outstandingAmount()).isEqualByComparingTo("30000.0000");
        assertThat(installment.status()).isEqualTo("DUE");
        assertThat(installment.paidAt()).isNull();
        assertThat(installment.paymentId()).isNull();
        assertThat(installment.createdAt()).isNotNull();

        BillingPlanView fetched = billingService.get(TENANT, policy.id());
        assertThat(fetched.plan()).isEqualTo(view.plan());
        assertThat(fetched.installments()).hasSize(4);
        assertThat(fetched.installments().getLast()).isEqualTo(installment);
        assertThat(fetched.installments()).extracting(PolicyInstallment::installmentNumber)
                .containsExactly(1, 2, 3, 4);
        assertThat(fetched.summary().totalAmount()).isEqualByComparingTo("150000.0000");
        assertThat(fetched.summary().outstandingAmount()).isEqualByComparingTo("150000.0000");
        assertThat(fetched.summary().installmentCount()).isEqualTo(4);
        assertThat(policyService.get(TENANT, policy.id()).version()).isEqualTo(policy.version() + 2);
    }

    @Test
    void writesTheAdjustedEventWithTheAppendedInstallmentFacts() {
        Policy policy = createPolicy("POL-ADJ-0002", new BigDecimal("120000.00"));
        BillingPlanView plan = billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(2, "ANNUAL", FIRST_DUE));
        raisePolicyPremium(policy.id(), new BigDecimal("135000.00"));

        BillingAdjustmentView view = billingService.adjust(TENANT, policy.id(),
                new BillingAdjustmentRequest(ADJUSTMENT_DUE, "mid-term exposure increase"));

        Map<String, Object> row = event("policy.billing-plan.adjusted", policy.id());
        assertThat(row.get("aggregate_type")).isEqualTo("Policy");
        assertThat(row.get("aggregate_version")).isEqualTo(3L);
        assertThat(row.get("status")).isEqualTo("PENDING");
        String payload = (String) row.get("payload");
        assertThat(payload)
                .contains("\"policyId\":\"" + policy.id() + "\"")
                .contains("\"policyNumber\":\"POL-ADJ-0002\"")
                .contains("\"planId\":\"" + plan.plan().id() + "\"")
                .contains("\"adjustmentAmount\":\"15000.0000\"")
                .contains("\"newTotalPremium\":\"135000.0000\"")
                .contains("\"installmentId\":\"" + view.installment().id() + "\"")
                .contains("\"installmentNumber\":3")
                .contains("\"dueDate\":\"2026-05-01\"")
                .contains("\"reason\":\"mid-term exposure increase\"");
        assertThat((String) row.get("headers")).contains("\"book\":\"DIRECT\"");

        OutboxEvent stored = outboxRepository
                .findById((UUID) row.get("id"))
                .orElseThrow();
        EventEnvelope envelope = stored.toEnvelope();
        assertThat(envelope.eventType()).isEqualTo("policy.billing-plan.adjusted");
        assertThat(envelope.book()).isEqualTo(Book.DIRECT);
        assertThat(envelope.aggregateId()).isEqualTo(policy.id());
        assertThat(envelope.aggregateVersion()).isEqualTo(3L);
        assertThat(envelope.data())
                .containsEntry("policyId", policy.id().toString())
                .containsEntry("adjustmentAmount", "15000.0000")
                .containsEntry("newTotalPremium", "135000.0000")
                .containsEntry("installmentNumber", 3)
                .containsEntry("dueDate", "2026-05-01")
                .containsEntry("reason", "mid-term exposure increase");
    }

    @Test
    void billsTheRemainderAboveTheNetPlanPremiumAfterCredits() {
        Policy policy = createPolicy("POL-ADJ-0012", new BigDecimal("120000.00"));
        billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(3, "MONTHLY", FIRST_DUE));
        applyCredit(policy, 1, new BigDecimal("12500.00"));
        applyCredit(policy, 2, new BigDecimal("7500.00"));
        raisePolicyPremium(policy.id(), new BigDecimal("150000.00"));

        BillingAdjustmentView view = billingService.adjust(TENANT, policy.id(),
                new BillingAdjustmentRequest(ADJUSTMENT_DUE, "credits already reduce the schedule"));

        // Net plan premium is 120000 - 20000 = 100000, so the appended amount is
        // 50000 and the gross plan total moves to 170000 (net 150000).
        assertThat(view.adjustmentAmount()).isEqualByComparingTo("50000.0000");
        assertThat(view.newTotalPremium()).isEqualByComparingTo("170000.0000");
        assertThat(view.plan().totalPremium()).isEqualByComparingTo("170000.0000");
        assertThat(view.plan().installmentCount()).isEqualTo(4);
        assertThat(view.installment().installmentNumber()).isEqualTo(4);
        assertThat(view.installment().amount()).isEqualByComparingTo("50000.0000");
        assertThat(view.installment().creditedAmount()).isEqualByComparingTo("0.0000");
        assertThat(view.installment().status()).isEqualTo("DUE");

        BillingPlanView fetched = billingService.get(TENANT, policy.id());
        assertThat(fetched.summary().totalAmount()).isEqualByComparingTo("170000.0000");
        assertThat(fetched.summary().creditedAmount()).isEqualByComparingTo("20000.0000");
        assertThat(fetched.summary().outstandingAmount()).isEqualByComparingTo("150000.0000");

        String payload = (String) event("policy.billing-plan.adjusted", policy.id()).get("payload");
        assertThat(payload)
                .contains("\"adjustmentAmount\":\"50000.0000\"")
                .contains("\"newTotalPremium\":\"170000.0000\"")
                .contains("\"installmentNumber\":4");
    }

    @Test
    void billsTheWholePolicyPremiumWhenThePlanIsFullyCredited() {
        Policy policy = createPolicy("POL-ADJ-0013", new BigDecimal("120000.00"));
        billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(1, "ANNUAL", FIRST_DUE));
        applyCredit(policy, 1, new BigDecimal("120000.00"));
        raisePolicyPremium(policy.id(), new BigDecimal("150000.00"));

        BillingAdjustmentView view = billingService.adjust(TENANT, policy.id(),
                new BillingAdjustmentRequest(ADJUSTMENT_DUE, "credits cover the schedule"));

        // The plan's net premium is zero, so the whole policy premium is billed
        // on a gross plan total that now exceeds the written premium.
        assertThat(view.adjustmentAmount()).isEqualByComparingTo("150000.0000");
        assertThat(view.newTotalPremium()).isEqualByComparingTo("270000.0000");
        assertThat(view.plan().totalPremium()).isEqualByComparingTo("270000.0000");
        assertThat(view.plan().installmentCount()).isEqualTo(2);
        assertThat(view.installment().installmentNumber()).isEqualTo(2);
        assertThat(view.installment().amount()).isEqualByComparingTo("150000.0000");

        assertThat((String) event("policy.billing-plan.adjusted", policy.id()).get("payload"))
                .contains("\"adjustmentAmount\":\"150000.0000\"")
                .contains("\"newTotalPremium\":\"270000.0000\"");
    }

    @Test
    void rejectsANonPositiveRemainderAgainstTheCreditedPlan() {
        Policy policy = createPolicy("POL-ADJ-0014", new BigDecimal("120000.00"));
        billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(3, "MONTHLY", FIRST_DUE));
        applyCredit(policy, 1, new BigDecimal("20000.00"));

        raisePolicyPremium(policy.id(), new BigDecimal("100000.00"));
        assertThatThrownBy(() -> billingService.adjust(TENANT, policy.id(),
                new BillingAdjustmentRequest(ADJUSTMENT_DUE, "net equal")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("NO_PREMIUM_ADJUSTMENT");
                    assertThat(api.meta())
                            .containsEntry("delta", "0.0000")
                            .containsEntry("policyPremiumAmount", "100000.0000")
                            .containsEntry("planTotalPremium", "120000.0000")
                            .containsEntry("creditedAmount", "20000.0000")
                            .containsEntry("netPlanPremium", "100000.0000");
                });

        raisePolicyPremium(policy.id(), new BigDecimal("90000.00"));
        assertThatThrownBy(() -> billingService.adjust(TENANT, policy.id(),
                new BillingAdjustmentRequest(ADJUSTMENT_DUE, "net negative")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("NO_PREMIUM_ADJUSTMENT");
                    assertThat(api.meta()).containsEntry("delta", "-10000.0000");
                });

        assertThat(count("policy_installment")).isEqualTo(3);
        assertThat(countEvents("policy.billing-plan.adjusted")).isZero();
    }

    @Test
    void rejectsAZeroRemainder() {
        Policy policy = createPolicy("POL-ADJ-0003", new BigDecimal("120000.00"));
        billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(3, "MONTHLY", FIRST_DUE));

        assertThatThrownBy(() -> billingService.adjust(TENANT, policy.id(),
                new BillingAdjustmentRequest(ADJUSTMENT_DUE, "no change")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("NO_PREMIUM_ADJUSTMENT");
                    assertThat(api.meta()).containsEntry("delta", "0.0000");
                });

        assertThat(count("policy_installment")).isEqualTo(3);
        assertThat(countEvents("policy.billing-plan.adjusted")).isZero();
        assertThat(policyService.get(TENANT, policy.id()).version()).isEqualTo(1);
    }

    @Test
    void rejectsANegativeRemainder() {
        Policy policy = createPolicy("POL-ADJ-0004", new BigDecimal("120000.00"));
        billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(2, "ANNUAL", FIRST_DUE));
        raisePolicyPremium(policy.id(), new BigDecimal("100000.00"));

        assertThatThrownBy(() -> billingService.adjust(TENANT, policy.id(),
                new BillingAdjustmentRequest(ADJUSTMENT_DUE, "premium reduced")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("NO_PREMIUM_ADJUSTMENT");
                    assertThat(api.meta())
                            .containsEntry("delta", "-20000.0000")
                            .containsEntry("policyPremiumAmount", "100000.0000")
                            .containsEntry("planTotalPremium", "120000.0000");
                });

        assertThat(count("policy_installment")).isEqualTo(2);
        assertThat(countEvents("policy.billing-plan.adjusted")).isZero();
    }

    @Test
    void returns404WhenThePolicyHasNoPlan() {
        Policy policy = createPolicy("POL-ADJ-0005", new BigDecimal("120000.00"));

        assertThatThrownBy(() -> billingService.adjust(TENANT, policy.id(),
                new BillingAdjustmentRequest(ADJUSTMENT_DUE, "audit")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.code()).isEqualTo("BILLING_PLAN_NOT_FOUND");
                });
    }

    @Test
    void rejectsANonActivePlan() {
        Policy policy = createPolicy("POL-ADJ-0006", new BigDecimal("120000.00"));
        BillingPlanView plan = billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(2, "ANNUAL", FIRST_DUE));
        raisePolicyPremium(policy.id(), new BigDecimal("150000.00"));
        jdbc.update("UPDATE policy_billing_plan SET status = 'CANCELLED' WHERE id = ?",
                plan.plan().id());

        assertThatThrownBy(() -> billingService.adjust(TENANT, policy.id(),
                new BillingAdjustmentRequest(ADJUSTMENT_DUE, "audit")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("BILLING_PLAN_NOT_ACTIVE");
                    assertThat(api.meta()).containsEntry("currentStatus", "CANCELLED");
                });

        assertThat(count("policy_installment")).isEqualTo(2);
        assertThat(countEvents("policy.billing-plan.adjusted")).isZero();
    }

    @Test
    void rejectsTheAdjustmentWhenTheScheduleAlreadyHasTwentyFourInstallments() {
        Policy policy = createPolicy("POL-ADJ-0007", new BigDecimal("120000.00"));
        billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(24, "MONTHLY", FIRST_DUE));
        raisePolicyPremium(policy.id(), new BigDecimal("150000.00"));

        assertThatThrownBy(() -> billingService.adjust(TENANT, policy.id(),
                new BillingAdjustmentRequest(ADJUSTMENT_DUE, "growth")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("MAX_INSTALLMENTS_REACHED");
                    assertThat(api.meta())
                            .containsEntry("maxInstallments", 24)
                            .containsEntry("installmentCount", 24);
                });

        assertThat(count("policy_installment")).isEqualTo(24);
        assertThat(countEvents("policy.billing-plan.adjusted")).isZero();
    }

    @Test
    void enforcesTheTenantWall() {
        Policy policy = createPolicy("POL-ADJ-0008", new BigDecimal("120000.00"));
        billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(2, "ANNUAL", FIRST_DUE));
        raisePolicyPremium(policy.id(), new BigDecimal("150000.00"));

        assertThatThrownBy(() -> billingService.adjust(OTHER_TENANT, policy.id(),
                new BillingAdjustmentRequest(ADJUSTMENT_DUE, "audit")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.code()).isEqualTo("POLICY_NOT_FOUND");
                });

        BillingAdjustmentView view = billingService.adjust(TENANT, policy.id(),
                new BillingAdjustmentRequest(ADJUSTMENT_DUE, "audit"));
        assertThat(view.installment().installmentNumber()).isEqualTo(3);
    }

    @Test
    void rejectsAnInvalidDueDateAndABlankReason() {
        Policy policy = createPolicy("POL-ADJ-0009", new BigDecimal("120000.00"));
        billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(2, "ANNUAL", FIRST_DUE));
        raisePolicyPremium(policy.id(), new BigDecimal("150000.00"));

        assertThatThrownBy(() -> billingService.adjust(TENANT, policy.id(),
                new BillingAdjustmentRequest(EFFECTIVE.minusDays(1), "audit")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("INVALID_ADJUSTMENT_DUE_DATE");
                });

        assertThatThrownBy(() -> billingService.adjust(TENANT, policy.id(),
                new BillingAdjustmentRequest(ADJUSTMENT_DUE, "   ")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("INVALID_ADJUSTMENT_REASON");
                });

        assertThat(count("policy_installment")).isEqualTo(2);
        assertThat(countEvents("policy.billing-plan.adjusted")).isZero();
    }

    @Test
    void acceptsThePolicyEffectiveDateAsTheEarliestDueDate() {
        Policy policy = createPolicy("POL-ADJ-0010", new BigDecimal("120000.00"));
        billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(2, "ANNUAL", FIRST_DUE));
        raisePolicyPremium(policy.id(), new BigDecimal("150000.00"));

        BillingAdjustmentView view = billingService.adjust(TENANT, policy.id(),
                new BillingAdjustmentRequest(EFFECTIVE, "inception correction"));

        assertThat(view.installment().dueDate()).isEqualTo(EFFECTIVE);
        assertThat(view.installment().amount()).isEqualByComparingTo("30000.0000");
    }

    @Test
    void adjustmentRollbackLeavesNoInstallmentPlanChangeOrEvent() {
        Policy policy = createPolicy("POL-ADJ-0011", new BigDecimal("120000.00"));
        billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(3, "MONTHLY", FIRST_DUE));
        raisePolicyPremium(policy.id(), new BigDecimal("150000.00"));
        int versionBefore = policyService.get(TENANT, policy.id()).version();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            billingService.adjust(TENANT, policy.id(),
                    new BillingAdjustmentRequest(ADJUSTMENT_DUE, "audit"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        BillingPlanView stored = billingService.get(TENANT, policy.id());
        assertThat(stored.installments()).hasSize(3);
        assertThat(stored.plan().totalPremium()).isEqualByComparingTo("120000.0000");
        assertThat(stored.plan().installmentCount()).isEqualTo(3);
        assertThat(countEvents("policy.billing-plan.adjusted")).isZero();
        assertThat(policyService.get(TENANT, policy.id()).version()).isEqualTo(versionBefore);
    }

    private Policy createPolicy(String number, BigDecimal premium) {
        return policyService.create(new CreatePolicyRequest(TENANT, number, "CGL", PARTY, null,
                EFFECTIVE, EXPIRATION, premium, "USD", new BigDecimal("5000000.00"), "USD"));
    }

    /**
     * Moves the written premium without touching the billing plan, which is the
     * state a premium audit or endorsement leaves for this command to bill.
     */
    private void raisePolicyPremium(UUID policyId, BigDecimal premium) {
        jdbc.update("UPDATE policy SET premium_amount = ? WHERE tenant_id = ? AND id = ?",
                premium, TENANT, policyId);
    }

    /**
     * Applies a real credit note to one installment, so the adjustment reads the
     * credited totals the credit-note slice writes rather than a test-only row
     * edit.
     */
    private void applyCredit(Policy policy, int installmentNumber, BigDecimal amount) {
        UUID installmentId = billingService.get(TENANT, policy.id()).installments().stream()
                .filter(row -> row.installmentNumber() == installmentNumber)
                .findFirst()
                .orElseThrow()
                .id();
        PolicyCreditNote note = creditNoteService.create(TENANT, policy.id(),
                new CreateCreditNoteRequest(amount, "USD", "premium credit"));
        creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("ISSUED"));
        creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("APPLIED", installmentId));
    }

    private Map<String, Object> event(String eventType, UUID policyId) {
        return jdbc.queryForMap("""
                SELECT id, aggregate_type, aggregate_version, status, payload, headers
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                """, policyId, eventType);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int countEvents(String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ?", Integer.class, eventType);
    }
}
