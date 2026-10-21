package com.eia.policy.expert;

import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tenant-wide premium receivables against H2: effective-status filters, the
 * inclusive due-date cutoff, stable worklist ordering, derived overdue status
 * and days past due, limit clamping, the tenant wall, and summary totals.
 */
@SpringBootTest
@ActiveProfiles("test")
class BillingReceivablesServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID PAYMENT = UUID.fromString("77777777-7777-7777-8777-777777777777");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);

    @Autowired
    private PolicyService policyService;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private BillingRepository billingRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private BillingService billingService;

    @BeforeEach
    void setUp() {
        for (String table : List.of(
                "outbox_event", "policy_credit_note", "policy_installment",
                "policy_billing_plan",
                "policy_limit_erosion", "policy_limit_state", "policy_limit",
                "policy_coverage", "policy_endorsement", "policy_insured", "policy_location",
                "policy_cancellation", "policy_reinstatement", "policy_version",
                "policy_command_idempotency", "policy_idempotency", "policy")) {
            jdbc.update("DELETE FROM " + table);
        }
        billingService = new BillingService(policyRepository, billingRepository,
                outboxRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void returnsOutstandingInstallmentsWithDerivedOverdueAndSummaryTotals() {
        Fixture fixture = seedFixture();

        ReceivablesView view = billingService.listReceivables(TENANT, null, null, 100);

        assertThat(view.items()).extracting(InstallmentReceivable::installmentId)
                .containsExactly(fixture.pastDue().id(), fixture.storedOverdue().id(),
                        fixture.dueToday().id(), fixture.partialCredited().id(),
                        fixture.futureDue().id());
        assertThat(view.items()).extracting(InstallmentReceivable::status)
                .containsExactly("OVERDUE", "OVERDUE", "DUE", "DUE", "DUE");
        assertThat(view.items()).extracting(InstallmentReceivable::daysPastDue)
                .containsExactly(10L, 3L, 0L, 0L, 0L);
        assertThat(view.totalAmount()).isEqualTo(new BigDecimal("11000.0000"));
        assertThat(view.overdueAmount()).isEqualTo(new BigDecimal("5000.0000"));
        assertThat(view.count()).isEqualTo(5);

        InstallmentReceivable derived = view.items().getFirst();
        assertThat(derived.planId()).isEqualTo(fixture.plan().id());
        assertThat(derived.planNumber()).isEqualTo("BP-REC-0001");
        assertThat(derived.policyId()).isEqualTo(fixture.policy().id());
        assertThat(derived.policyNumber()).isEqualTo("POL-REC-0001");
        assertThat(derived.insuredPartyId()).isEqualTo(PARTY);
        assertThat(derived.installmentNumber()).isEqualTo(1);
        assertThat(derived.dueDate()).isEqualTo(TODAY.minusDays(10));
        assertThat(derived.amount()).isEqualByComparingTo("1000.0000");
        assertThat(derived.currency()).isEqualTo("USD");
        assertThat(derived.paidAt()).isNull();
        assertThat(derived.paymentId()).isNull();
        assertThat(derived.creditedAmount()).isEqualByComparingTo("0.0000");
        assertThat(derived.outstandingAmount()).isEqualByComparingTo("1000.0000");

        InstallmentReceivable partial = view.items().get(3);
        assertThat(partial.status()).isEqualTo("DUE");
        assertThat(partial.amount()).isEqualByComparingTo("1500.0000");
        assertThat(partial.creditedAmount()).isEqualByComparingTo("500.0000");
        assertThat(partial.outstandingAmount()).isEqualByComparingTo("1000.0000");

        assertThat(view.items()).extracting(InstallmentReceivable::installmentId)
                .doesNotContain(fixture.paid().id(), fixture.waived().id(),
                        fixture.fullyCredited().id());
        assertThat(storedStatus(fixture.pastDue().id())).isEqualTo("DUE");
        assertThat(storedStatus(fixture.storedOverdue().id())).isEqualTo("OVERDUE");
        assertThat(storedStatus(fixture.fullyCredited().id())).isEqualTo("CREDITED");
    }

    @Test
    void filtersByEffectiveStatusAndInclusiveDueDate() {
        Fixture fixture = seedFixture();

        ReceivablesView due = billingService.listReceivables(TENANT, "due", null, 100);
        assertThat(due.items()).extracting(InstallmentReceivable::installmentId)
                .containsExactly(fixture.dueToday().id(), fixture.partialCredited().id(),
                        fixture.futureDue().id());
        assertThat(due.items()).extracting(InstallmentReceivable::status)
                .containsOnly("DUE");
        assertThat(due.totalAmount()).isEqualTo(new BigDecimal("6000.0000"));
        assertThat(due.overdueAmount()).isEqualTo(new BigDecimal("0.0000"));

        ReceivablesView overdue = billingService.listReceivables(TENANT, "OVERDUE", null, 100);
        assertThat(overdue.items()).extracting(InstallmentReceivable::installmentId)
                .containsExactly(fixture.pastDue().id(), fixture.storedOverdue().id());
        assertThat(overdue.items()).extracting(InstallmentReceivable::status)
                .containsOnly("OVERDUE");
        assertThat(overdue.totalAmount()).isEqualTo(new BigDecimal("5000.0000"));
        assertThat(overdue.overdueAmount()).isEqualTo(new BigDecimal("5000.0000"));

        ReceivablesView cutoff = billingService.listReceivables(TENANT, null, TODAY, 100);
        assertThat(cutoff.items()).extracting(InstallmentReceivable::installmentId)
                .containsExactly(fixture.pastDue().id(), fixture.storedOverdue().id(),
                        fixture.dueToday().id());
        assertThat(cutoff.count()).isEqualTo(3);

        assertThat(billingService.listReceivables(TENANT, null, TODAY.minusDays(10), 100).items())
                .extracting(InstallmentReceivable::installmentId)
                .containsExactly(fixture.pastDue().id());
        assertThat(billingService.listReceivables(TENANT, null, TODAY.minusDays(11), 100).items())
                .isEmpty();
    }

    @Test
    void rejectsAnUnknownStatus() {
        assertThatThrownBy(() -> billingService.listReceivables(TENANT, "PAID", null, 100))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("INVALID_INSTALLMENT_STATUS");
                    assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                            .containsExactly("status");
                });
    }

    @Test
    void ordersByDueDateThenPolicyNumberThenInstallmentNumber() {
        Policy alpha = createPolicy(TENANT, "POL-REC-0100");
        PolicyBillingPlan alphaPlan = createPlan(alpha, "BP-ALPHA");
        PolicyInstallment alphaTwo = insertInstallment(alphaPlan, 2, TODAY.plusDays(5),
                "200.0000", "DUE", null, null);
        PolicyInstallment alphaOne = insertInstallment(alphaPlan, 1, TODAY.plusDays(5),
                "100.0000", "DUE", null, null);

        Policy bravo = createPolicy(TENANT, "POL-REC-0200");
        PolicyBillingPlan bravoPlan = createPlan(bravo, "BP-BRAVO");
        PolicyInstallment bravoOne = insertInstallment(bravoPlan, 1, TODAY.plusDays(2),
                "50.0000", "DUE", null, null);

        Policy charlie = createPolicy(TENANT, "POL-REC-0150");
        PolicyBillingPlan charliePlan = createPlan(charlie, "BP-CHARLIE");
        PolicyInstallment charlieOne = insertInstallment(charliePlan, 1, TODAY.plusDays(5),
                "300.0000", "DUE", null, null);

        ReceivablesView view = billingService.listReceivables(TENANT, null, null, 100);

        assertThat(view.items()).extracting(InstallmentReceivable::installmentId)
                .containsExactly(bravoOne.id(), alphaOne.id(), alphaTwo.id(), charlieOne.id());
    }

    @Test
    void clampsTheLimitToOneAndCapsItAtTwoHundred() {
        Policy policy = createPolicy(TENANT, "POL-REC-0300");
        PolicyBillingPlan plan = createPlan(policy, "BP-LIMIT");
        for (int number = 1; number <= 205; number++) {
            insertInstallment(plan, number, TODAY.plusDays(number % 7), "10.0000",
                    "DUE", null, null);
        }

        ReceivablesView capped = billingService.listReceivables(TENANT, null, null, 500);
        assertThat(capped.items()).hasSize(200);
        assertThat(capped.count()).isEqualTo(200);
        assertThat(capped.totalAmount()).isEqualTo(new BigDecimal("2000.0000"));

        assertThat(billingService.listReceivables(TENANT, null, null, 3).items()).hasSize(3);
        assertThat(billingService.listReceivables(TENANT, null, null, 0).items()).hasSize(1);
        assertThat(billingService.listReceivables(TENANT, null, null, -5).items()).hasSize(1);
    }

    @Test
    void enforcesTheTenantWallAcrossThePlanPolicyAndInstallmentTables() {
        Policy mine = createPolicy(TENANT, "POL-REC-0400");
        PolicyBillingPlan myPlan = createPlan(mine, "BP-MINE");
        PolicyInstallment myRow = insertInstallment(myPlan, 1, TODAY.plusDays(1),
                "100.0000", "DUE", null, null);

        Policy other = createPolicy(OTHER_TENANT, "POL-REC-0401");
        PolicyBillingPlan otherPlan = createPlan(other, "BP-OTHER");
        PolicyInstallment otherRow = insertInstallment(otherPlan, 1, TODAY.plusDays(1),
                "200.0000", "DUE", null, null);

        // The installment tenant disagrees with the plan/policy tenant, so neither
        // tenant may see the row even though it carries a matching tenant column.
        insertInstallment(otherPlan, 2, TODAY.plusDays(1), "300.0000", "DUE", null, null,
                TENANT);

        assertThat(billingService.listReceivables(TENANT, null, null, 100).items())
                .extracting(InstallmentReceivable::installmentId)
                .containsExactly(myRow.id());
        assertThat(billingService.listReceivables(OTHER_TENANT, null, null, 100).items())
                .extracting(InstallmentReceivable::installmentId)
                .containsExactly(otherRow.id());
    }

    @Test
    void returnsZeroedSummaryWhenNoInstallmentMatches() {
        createPolicy(TENANT, "POL-REC-0500");

        ReceivablesView view = billingService.listReceivables(TENANT, null, null, 100);

        assertThat(view.items()).isEmpty();
        assertThat(view.totalAmount()).isEqualTo(new BigDecimal("0.0000"));
        assertThat(view.overdueAmount()).isEqualTo(new BigDecimal("0.0000"));
        assertThat(view.count()).isZero();
    }

    private Fixture seedFixture() {
        Policy policy = createPolicy(TENANT, "POL-REC-0001");
        PolicyBillingPlan plan = createPlan(policy, "BP-REC-0001");
        PolicyInstallment pastDue = insertInstallment(plan, 1, TODAY.minusDays(10),
                "1000.0000", "DUE", null, null);
        PolicyInstallment dueToday = insertInstallment(plan, 2, TODAY,
                "2000.0000", "DUE", null, null);
        PolicyInstallment futureDue = insertInstallment(plan, 3, TODAY.plusDays(10),
                "3000.0000", "DUE", null, null);
        PolicyInstallment storedOverdue = insertInstallment(plan, 4, TODAY.minusDays(3),
                "4000.0000", "OVERDUE", null, null);
        PolicyInstallment paid = insertInstallment(plan, 5, TODAY.plusDays(20),
                "5000.0000", "PAID", NOW, PAYMENT);
        PolicyInstallment waived = insertInstallment(plan, 6, TODAY.plusDays(25),
                "6000.0000", "WAIVED", null, null);
        PolicyInstallment partialCredited = insertCreditedInstallment(plan, 7, TODAY.plusDays(4),
                "1500.0000", "500.0000", "DUE");
        PolicyInstallment fullyCredited = insertCreditedInstallment(plan, 8, TODAY.plusDays(30),
                "700.0000", "700.0000", "CREDITED");
        return new Fixture(policy, plan, pastDue, dueToday, futureDue, storedOverdue, paid,
                waived, partialCredited, fullyCredited);
    }

    private Policy createPolicy(UUID tenantId, String policyNumber) {
        return policyService.create(new CreatePolicyRequest(tenantId, policyNumber, "CGL", PARTY,
                null, EFFECTIVE, EXPIRATION, new BigDecimal("120000.00"), "USD",
                new BigDecimal("5000000.00"), "USD"));
    }

    private PolicyBillingPlan createPlan(Policy policy, String planNumber) {
        PolicyBillingPlan plan = new PolicyBillingPlan(UUID.randomUUID(), policy.tenantId(),
                policy.id(), planNumber, new BigDecimal("120000.0000"), "USD", 24, "MONTHLY",
                TODAY.minusDays(30), "ACTIVE", NOW);
        billingRepository.insertPlan(plan);
        return plan;
    }

    private PolicyInstallment insertInstallment(PolicyBillingPlan plan, int number,
                                                LocalDate dueDate, String amount, String status,
                                                Instant paidAt, UUID paymentId) {
        return insertInstallment(plan, number, dueDate, amount, status, paidAt, paymentId,
                plan.tenantId());
    }

    private PolicyInstallment insertInstallment(PolicyBillingPlan plan, int number,
                                                LocalDate dueDate, String amount, String status,
                                                Instant paidAt, UUID paymentId, UUID tenantId) {
        PolicyInstallment installment = new PolicyInstallment(UUID.randomUUID(), tenantId,
                plan.id(), number, dueDate, new BigDecimal(amount), status, paidAt, paymentId,
                NOW);
        billingRepository.insertInstallment(installment);
        return installment;
    }

    private PolicyInstallment insertCreditedInstallment(PolicyBillingPlan plan, int number,
                                                        LocalDate dueDate, String amount,
                                                        String creditedAmount, String status) {
        PolicyInstallment installment = new PolicyInstallment(UUID.randomUUID(), plan.tenantId(),
                plan.id(), number, dueDate, new BigDecimal(amount),
                new BigDecimal(creditedAmount), status, null, null, NOW);
        billingRepository.insertInstallment(installment);
        return installment;
    }

    private String storedStatus(UUID installmentId) {
        return jdbc.queryForObject("SELECT status FROM policy_installment WHERE id = ?",
                String.class, installmentId);
    }

    private record Fixture(Policy policy, PolicyBillingPlan plan, PolicyInstallment pastDue,
                           PolicyInstallment dueToday, PolicyInstallment futureDue,
                           PolicyInstallment storedOverdue, PolicyInstallment paid,
                           PolicyInstallment waived, PolicyInstallment partialCredited,
                           PolicyInstallment fullyCredited) {
    }
}
