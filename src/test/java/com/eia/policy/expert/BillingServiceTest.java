package com.eia.policy.expert;

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
 * End-to-end billing slice against H2: plan creation and rounding, duplicate
 * plan conflict, reads, installment settlement and idempotency, tenant wall,
 * outbox payloads, and transactional rollback.
 */
@SpringBootTest
@ActiveProfiles("test")
class BillingServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID PAYMENT = UUID.fromString("77777777-7777-7777-8777-777777777777");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);
    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 2, 1);

    @Autowired
    private BillingService billingService;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        for (String table : List.of(
                "outbox_event", "policy_installment", "policy_billing_plan",
                "policy_limit_erosion", "policy_limit_state", "policy_limit",
                "policy_coverage", "policy_endorsement", "policy_insured", "policy_location",
                "policy_cancellation", "policy_reinstatement", "policy_version",
                "policy_command_idempotency", "policy_idempotency", "policy")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void createsAPlanWithAnEqualSplitAndRemainderOnTheLastInstallment() {
        Policy policy = createPolicy("POL-BILL-0001", new BigDecimal("100000.00"));

        BillingPlanView view = billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(3, "MONTHLY", FIRST_DUE));

        assertThat(view.plan().planNumber()).isEqualTo("BP-0001");
        assertThat(view.plan().policyId()).isEqualTo(policy.id());
        assertThat(view.plan().totalPremium()).isEqualByComparingTo("100000.0000");
        assertThat(view.plan().currency()).isEqualTo("USD");
        assertThat(view.plan().installmentCount()).isEqualTo(3);
        assertThat(view.plan().frequency()).isEqualTo("MONTHLY");
        assertThat(view.plan().firstDueDate()).isEqualTo(FIRST_DUE);
        assertThat(view.plan().status()).isEqualTo("ACTIVE");
        assertThat(view.plan().createdAt()).isNotNull();

        assertThat(view.installments()).hasSize(3);
        assertThat(view.installments()).extracting(PolicyInstallment::installmentNumber)
                .containsExactly(1, 2, 3);
        assertThat(view.installments()).extracting(PolicyInstallment::amount)
                .containsExactly(new BigDecimal("33333.3333"), new BigDecimal("33333.3333"),
                        new BigDecimal("33333.3334"));
        assertThat(sum(view.installments())).isEqualByComparingTo("100000.0000");
        assertThat(view.installments()).extracting(PolicyInstallment::dueDate)
                .containsExactly(FIRST_DUE, LocalDate.of(2026, 3, 1),
                        LocalDate.of(2026, 4, 1));
        assertThat(view.installments()).allSatisfy(installment -> {
            assertThat(installment.status()).isEqualTo("DUE");
            assertThat(installment.paidAt()).isNull();
            assertThat(installment.paymentId()).isNull();
        });

        assertThat(policyService.get(TENANT, policy.id()).version()).isEqualTo(policy.version() + 1);
    }

    @Test
    void rejectsASecondActivePlanWithConflict() {
        Policy policy = createPolicy("POL-BILL-0002", new BigDecimal("120000.00"));
        billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(2, "ANNUAL", FIRST_DUE));

        assertThatThrownBy(() -> billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(4, "QUARTERLY", FIRST_DUE)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("BILLING_PLAN_ALREADY_EXISTS");
                });

        assertThat(count("policy_billing_plan")).isEqualTo(1);
        assertThat(count("policy_installment")).isEqualTo(2);
    }

    @Test
    void getsTheCurrentPlanAndItsInstallments() {
        Policy policy = createPolicy("POL-BILL-0003", new BigDecimal("90000.00"));

        assertThatThrownBy(() -> billingService.get(TENANT, policy.id()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.code()).isEqualTo("BILLING_PLAN_NOT_FOUND");
                });

        BillingPlanView created = billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(3, "QUARTERLY", FIRST_DUE));
        BillingPlanView fetched = billingService.get(TENANT, policy.id());

        assertThat(fetched.plan()).isEqualTo(created.plan());
        assertThat(fetched.installments()).isEqualTo(created.installments());
    }

    @Test
    void marksAnInstallmentPaidAndWritesThePaidEvent() {
        Policy policy = createPolicy("POL-BILL-0004", new BigDecimal("120000.00"));
        BillingPlanView view = billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(3, "MONTHLY", FIRST_DUE));
        PolicyInstallment first = view.installments().getFirst();

        PolicyInstallment paid = billingService.updateInstallment(TENANT, policy.id(), first.id(),
                new UpdateInstallmentRequest("PAID", PAYMENT));

        assertThat(paid.status()).isEqualTo("PAID");
        assertThat(paid.paidAt()).isNotNull();
        assertThat(paid.paymentId()).isEqualTo(PAYMENT);
        assertThat(paid.amount()).isEqualByComparingTo("40000.0000");

        Map<String, Object> event = event("policy.installment-paid", policy.id());
        assertThat(event.get("aggregate_type")).isEqualTo("Policy");
        assertThat(event.get("aggregate_version")).isEqualTo(3L);
        assertThat(event.get("status")).isEqualTo("PENDING");
        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"policyId\":\"" + policy.id() + "\"")
                .contains("\"policyNumber\":\"POL-BILL-0004\"")
                .contains("\"planId\":\"" + view.plan().id() + "\"")
                .contains("\"planNumber\":\"BP-0001\"")
                .contains("\"installmentId\":\"" + first.id() + "\"")
                .contains("\"number\":1")
                .contains("\"amount\":\"40000.0000\"")
                .contains("\"currency\":\"USD\"")
                .contains("\"status\":\"PAID\"")
                .contains("\"paymentId\":\"" + PAYMENT + "\"");
        assertThat((String) event.get("headers")).contains("\"book\":\"DIRECT\"");
    }

    @Test
    void marksAnInstallmentWaivedWithoutPaymentEvidence() {
        Policy policy = createPolicy("POL-BILL-0005", new BigDecimal("120000.00"));
        BillingPlanView view = billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(2, "SEMI_ANNUAL", FIRST_DUE));
        PolicyInstallment first = view.installments().getFirst();

        PolicyInstallment waived = billingService.updateInstallment(TENANT, policy.id(),
                first.id(), new UpdateInstallmentRequest("WAIVED", PAYMENT));

        assertThat(waived.status()).isEqualTo("WAIVED");
        assertThat(waived.paidAt()).isNull();
        assertThat(waived.paymentId()).isNull();

        String payload = (String) event("policy.installment-waived", policy.id()).get("payload");
        assertThat(payload)
                .contains("\"installmentId\":\"" + first.id() + "\"")
                .contains("\"status\":\"WAIVED\"")
                .contains("\"amount\":\"60000.0000\"")
                .doesNotContain("\"paymentId\"");
    }

    @Test
    void repeatingTheSameTerminalStatusIsAnIdempotentNoOp() {
        Policy policy = createPolicy("POL-BILL-0006", new BigDecimal("120000.00"));
        BillingPlanView view = billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(2, "ANNUAL", FIRST_DUE));
        PolicyInstallment first = view.installments().getFirst();
        PolicyInstallment paid = billingService.updateInstallment(TENANT, policy.id(), first.id(),
                new UpdateInstallmentRequest("PAID", PAYMENT));
        int versionAfterPayment = policyService.get(TENANT, policy.id()).version();
        int paidEvents = countEvents("policy.installment-paid");

        PolicyInstallment replay = billingService.updateInstallment(TENANT, policy.id(), first.id(),
                new UpdateInstallmentRequest("PAID", UUID.randomUUID()));

        assertThat(replay).isEqualTo(paid);
        assertThat(policyService.get(TENANT, policy.id()).version()).isEqualTo(versionAfterPayment);
        assertThat(countEvents("policy.installment-paid")).isEqualTo(paidEvents);
        assertThat(policyService.get(TENANT, policy.id()).version()).isEqualTo(versionAfterPayment);
    }

    @Test
    void rejectsChangingATerminalStatusToTheOtherTerminalStatus() {
        Policy policy = createPolicy("POL-BILL-0007", new BigDecimal("120000.00"));
        BillingPlanView view = billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(2, "ANNUAL", FIRST_DUE));
        PolicyInstallment first = view.installments().getFirst();
        billingService.updateInstallment(TENANT, policy.id(), first.id(),
                new UpdateInstallmentRequest("PAID", PAYMENT));

        assertThatThrownBy(() -> billingService.updateInstallment(TENANT, policy.id(), first.id(),
                new UpdateInstallmentRequest("WAIVED", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("INSTALLMENT_ALREADY_SETTLED");
                    assertThat(api.meta()).containsEntry("currentStatus", "PAID");
                });
    }

    @Test
    void rejectsInvalidCountsFrequencyAndStatus() {
        Policy policy = createPolicy("POL-BILL-0008", new BigDecimal("120000.00"));

        assertBadRequest(() -> billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(0, "MONTHLY", FIRST_DUE)), "INVALID_INSTALLMENT_COUNT");
        assertBadRequest(() -> billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(25, "MONTHLY", FIRST_DUE)), "INVALID_INSTALLMENT_COUNT");
        assertBadRequest(() -> billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(2, "WEEKLY", FIRST_DUE)), "INVALID_BILLING_FREQUENCY");
        assertBadRequest(() -> billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(2, "MONTHLY", null)), "INVALID_FIRST_DUE_DATE");

        BillingPlanView view = billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(2, "monthly", FIRST_DUE));
        assertThat(view.plan().frequency()).isEqualTo("MONTHLY");
        assertBadRequest(() -> billingService.updateInstallment(TENANT, policy.id(),
                        view.installments().getFirst().id(),
                        new UpdateInstallmentRequest("DUE", null)),
                "INVALID_INSTALLMENT_STATUS");
    }

    @Test
    void enforcesTheTenantWallOnReadsAndWrites() {
        Policy policy = createPolicy("POL-BILL-0009", new BigDecimal("120000.00"));
        BillingPlanView view = billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(2, "ANNUAL", FIRST_DUE));
        PolicyInstallment first = view.installments().getFirst();

        assertNotFound(() -> billingService.get(OTHER_TENANT, policy.id()));
        assertNotFound(() -> billingService.create(OTHER_TENANT, policy.id(),
                new CreateBillingPlanRequest(2, "ANNUAL", FIRST_DUE)));
        assertNotFound(() -> billingService.updateInstallment(OTHER_TENANT, policy.id(), first.id(),
                new UpdateInstallmentRequest("PAID", PAYMENT)));

        Policy otherPolicy = createPolicy("POL-BILL-0010", new BigDecimal("50000.00"));
        assertNotFound(() -> billingService.updateInstallment(TENANT, otherPolicy.id(), first.id(),
                new UpdateInstallmentRequest("PAID", PAYMENT)));
    }

    @Test
    void planCreateRollbackLeavesNoPlanSchedulePolicyChangeOrEvent() {
        Policy policy = createPolicy("POL-BILL-0011", new BigDecimal("120000.00"));
        int versionBefore = policy.version();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            billingService.create(TENANT, policy.id(),
                    new CreateBillingPlanRequest(3, "MONTHLY", FIRST_DUE));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("policy_billing_plan")).isZero();
        assertThat(count("policy_installment")).isZero();
        assertThat(countEvents("policy.billing-plan-created")).isZero();
        assertThat(policyService.get(TENANT, policy.id()).version()).isEqualTo(versionBefore);
    }

    @Test
    void installmentRollbackLeavesTheRowDueAndWritesNoEvent() {
        Policy policy = createPolicy("POL-BILL-0012", new BigDecimal("120000.00"));
        BillingPlanView view = billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(2, "ANNUAL", FIRST_DUE));
        PolicyInstallment first = view.installments().getFirst();
        int versionBefore = policyService.get(TENANT, policy.id()).version();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            billingService.updateInstallment(TENANT, policy.id(), first.id(),
                    new UpdateInstallmentRequest("PAID", PAYMENT));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        PolicyInstallment stored = billingService.get(TENANT, policy.id()).installments()
                .getFirst();
        assertThat(stored.status()).isEqualTo("DUE");
        assertThat(stored.paidAt()).isNull();
        assertThat(stored.paymentId()).isNull();
        assertThat(countEvents("policy.installment-paid")).isZero();
        assertThat(policyService.get(TENANT, policy.id()).version()).isEqualTo(versionBefore);
    }

    @Test
    void everySettledInstallmentWritesADistinctOutboxEvent() {
        Policy policy = createPolicy("POL-BILL-0013", new BigDecimal("120000.00"));
        BillingPlanView view = billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(3, "MONTHLY", FIRST_DUE));
        String planPayload =
                (String) event("policy.billing-plan-created", policy.id()).get("payload");
        assertThat(planPayload)
                .contains("\"policyId\":\"" + policy.id() + "\"")
                .contains("\"policyNumber\":\"POL-BILL-0013\"")
                .contains("\"planId\":\"" + view.plan().id() + "\"")
                .contains("\"planNumber\":\"BP-0001\"")
                .contains("\"totalPremium\":\"120000.0000\"")
                .contains("\"installmentCount\":3")
                .contains("\"frequency\":\"MONTHLY\"")
                .contains("\"firstDueDate\":\"2026-02-01\"");
        assertThat(planPayload.split("\"installmentId\"", -1)).hasSize(4);

        billingService.updateInstallment(TENANT, policy.id(),
                view.installments().get(0).id(), new UpdateInstallmentRequest("PAID", PAYMENT));
        billingService.updateInstallment(TENANT, policy.id(),
                view.installments().get(1).id(), new UpdateInstallmentRequest("PAID", PAYMENT));

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = 'policy.installment-paid'
                 ORDER BY event_sequence
                """, policy.id());
        assertThat(events).hasSize(2);
        assertThat(events).extracting(row -> row.get("aggregate_version"))
                .containsExactly(3L, 4L);
    }

    private Policy createPolicy(String number, BigDecimal premium) {
        return policyService.create(new CreatePolicyRequest(TENANT, number, "CGL", PARTY, null,
                EFFECTIVE, EXPIRATION, premium, "USD", new BigDecimal("5000000.00"), "USD"));
    }

    private Map<String, Object> event(String eventType, UUID policyId) {
        return jdbc.queryForMap("""
                SELECT aggregate_type, aggregate_version, status, payload, headers
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

    private static BigDecimal sum(List<PolicyInstallment> installments) {
        return installments.stream().map(PolicyInstallment::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void assertBadRequest(ThrowingCall call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(ApiException.class).satisfies(ex -> {
            ApiException api = (ApiException) ex;
            assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(api.code()).isEqualTo(code);
        });
    }

    private static void assertNotFound(ThrowingCall call) {
        assertThatThrownBy(call::run).isInstanceOf(ApiException.class).satisfies(ex -> {
            ApiException api = (ApiException) ex;
            assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
        });
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
