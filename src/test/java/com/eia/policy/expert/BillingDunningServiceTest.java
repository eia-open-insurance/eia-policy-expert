package com.eia.policy.expert;

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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end dunning slice against H2: create and list, duplicate-level and
 * settled-installment conflicts, effective DUE/OVERDUE handling, receivables
 * enrichment, the tenant wall, the outbox payload, and transactional rollback.
 */
@SpringBootTest
@ActiveProfiles("test")
class BillingDunningServiceTest extends ProductCatalogTestSupport {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);
    private static final String EVENT_TYPE = "policy.dunning.sent";

    @Autowired
    private PolicyService policyService;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private BillingRepository billingRepository;

    @Autowired
    private BillingDunningRepository dunningRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private BillingDunningService dunningService;
    private BillingService billingService;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        for (String table : List.of(
                "billing_dunning_action", "outbox_event", "policy_credit_note",
                "policy_installment", "policy_billing_plan", "policy_limit_erosion",
                "policy_limit_state",
                "policy_limit", "policy_coverage", "policy_endorsement", "policy_insured",
                "policy_location", "policy_cancellation", "policy_reinstatement",
                "policy_version", "policy_command_idempotency", "policy_idempotency",
                "policy")) {
            jdbc.update("DELETE FROM " + table);
        }
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        dunningService = new BillingDunningService(policyRepository, dunningRepository,
                outboxRepository, clock);
        billingService = new BillingService(policyRepository, billingRepository,
                outboxRepository, clock);
        transaction = new TransactionTemplate(transactionManager);
    }

    @Test
    void createsAndListsDunningActionsNewestFirst() {
        Fixture fixture = seedFixture();

        BillingDunningAction reminder = dunningService.create(TENANT, fixture.first().id(),
                new CreateDunningActionRequest(" reminder ", " email ", "  First reminder  "));

        assertThat(reminder.id()).isNotNull();
        assertThat(reminder.tenantId()).isEqualTo(TENANT);
        assertThat(reminder.installmentId()).isEqualTo(fixture.first().id());
        assertThat(reminder.planId()).isEqualTo(fixture.plan().id());
        assertThat(reminder.policyId()).isEqualTo(fixture.policy().id());
        assertThat(reminder.level()).isEqualTo("REMINDER");
        assertThat(reminder.channel()).isEqualTo("EMAIL");
        assertThat(reminder.notes()).isEqualTo("First reminder");
        assertThat(reminder.status()).isEqualTo("SENT");
        assertThat(reminder.sentAt()).isEqualTo(NOW);
        assertThat(reminder.createdAt()).isEqualTo(NOW);

        BillingDunningAction notice = dunningService.create(TENANT, fixture.first().id(),
                new CreateDunningActionRequest("FIRST_NOTICE", "LETTER", null));
        assertThat(notice.notes()).isNull();

        // Both actions use the fixed clock; move the reminder back so sent_at
        // alone determines the newest-first order.
        jdbc.update("UPDATE billing_dunning_action SET sent_at = ? WHERE id = ?",
                Timestamp.from(NOW.minusSeconds(60)), reminder.id());

        List<BillingDunningAction> history = dunningService.list(TENANT, fixture.first().id());
        assertThat(history).extracting(BillingDunningAction::id)
                .containsExactly(notice.id(), reminder.id());
        assertThat(history).extracting(BillingDunningAction::level)
                .containsExactly("FIRST_NOTICE", "REMINDER");

        assertThat(dunningService.list(TENANT, fixture.second().id())).isEmpty();
    }

    @Test
    void rejectsARepeatedLevelWithADuplicateConflict() {
        Fixture fixture = seedFixture();
        BillingDunningAction reminder = dunningService.create(TENANT, fixture.first().id(),
                new CreateDunningActionRequest("REMINDER", "EMAIL", null));
        int versionAfterFirst = policyService.get(TENANT, fixture.policy().id()).version();

        assertThatThrownBy(() -> dunningService.create(TENANT, fixture.first().id(),
                new CreateDunningActionRequest("REMINDER", "PORTAL", "second attempt")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("DUNNING_ALREADY_SENT");
                    assertThat(api.meta()).containsEntry("level", "REMINDER");
                });

        assertThat(count("billing_dunning_action")).isEqualTo(1);
        assertThat(countEvents()).isEqualTo(1);
        assertThat(policyService.get(TENANT, fixture.policy().id()).version())
                .isEqualTo(versionAfterFirst);
        assertThat(dunningRepository.findByLevel(TENANT, fixture.first().id(), "REMINDER"))
                .get().extracting(BillingDunningAction::id).isEqualTo(reminder.id());
    }

    @Test
    void rejectsPaidAndWaivedInstallments() {
        Policy policy = createPolicy(TENANT, "POL-DUN-0100");
        PolicyBillingPlan plan = createPlan(policy, "BP-DUN-0100");
        PolicyInstallment paid = insertInstallment(plan, 1, TODAY.minusDays(5), "1000.0000",
                "PAID", NOW.minusSeconds(600), UUID.randomUUID());
        PolicyInstallment waived = insertInstallment(plan, 2, TODAY.minusDays(3), "2000.0000",
                "WAIVED", null, null);

        assertNotDunnable(paid.id(), "PAID");
        assertNotDunnable(waived.id(), "WAIVED");
        assertThat(count("billing_dunning_action")).isZero();
        assertThat(countEvents()).isZero();
    }

    @Test
    void allowsStoredDuePastDueAndStoredOverdueInstallments() {
        Fixture fixture = seedFixture();
        PolicyInstallment storedOverdue = insertInstallment(fixture.plan(), 4,
                TODAY.minusDays(1), "4000.0000", "OVERDUE", null, null);

        BillingDunningAction pastDue = dunningService.create(TENANT, fixture.first().id(),
                new CreateDunningActionRequest("REMINDER", "EMAIL", null));
        BillingDunningAction futureDue = dunningService.create(TENANT, fixture.second().id(),
                new CreateDunningActionRequest("REMINDER", "LETTER", null));
        BillingDunningAction overdue = dunningService.create(TENANT, storedOverdue.id(),
                new CreateDunningActionRequest("REMINDER", "PORTAL", null));

        assertThat(pastDue.level()).isEqualTo("REMINDER");
        assertThat(futureDue.installmentId()).isEqualTo(fixture.second().id());
        assertThat(overdue.installmentId()).isEqualTo(storedOverdue.id());
        assertThat(count("billing_dunning_action")).isEqualTo(3);
    }

    @Test
    void enrichesReceivablesWithTheLatestSentDunningAction() {
        Fixture fixture = seedFixture();
        BillingDunningAction reminder = dunningService.create(TENANT, fixture.first().id(),
                new CreateDunningActionRequest("REMINDER", "EMAIL", null));
        BillingDunningAction notice = dunningService.create(TENANT, fixture.first().id(),
                new CreateDunningActionRequest("FIRST_NOTICE", "LETTER", null));
        dunningService.create(TENANT, fixture.second().id(),
                new CreateDunningActionRequest("REMINDER", "PORTAL", null));

        jdbc.update("UPDATE billing_dunning_action SET sent_at = ? WHERE id = ?",
                Timestamp.from(NOW.minusSeconds(60)), reminder.id());
        // Cancelled history is newer but must not count as the last SENT action.
        dunningRepository.insert(new BillingDunningAction(UUID.randomUUID(), TENANT,
                fixture.first().id(), fixture.plan().id(), fixture.policy().id(), "COLLECTIONS",
                "PORTAL", "superseded", "CANCELLED", NOW.plusSeconds(3600),
                NOW.plusSeconds(3600)));
        dunningRepository.insert(new BillingDunningAction(UUID.randomUUID(), TENANT,
                fixture.third().id(), fixture.plan().id(), fixture.policy().id(), "REMINDER",
                "EMAIL", "superseded", "CANCELLED", NOW.plusSeconds(7200),
                NOW.plusSeconds(7200)));

        ReceivablesView view = billingService.listReceivables(TENANT, null, null, 100);

        InstallmentReceivable first = item(view, fixture.first().id());
        assertThat(first.status()).isEqualTo("OVERDUE");
        assertThat(first.lastDunningLevel()).isEqualTo("FIRST_NOTICE");
        assertThat(first.lastDunningAt()).isEqualTo(notice.sentAt());

        InstallmentReceivable second = item(view, fixture.second().id());
        assertThat(second.status()).isEqualTo("DUE");
        assertThat(second.lastDunningLevel()).isEqualTo("REMINDER");
        assertThat(second.lastDunningAt()).isEqualTo(NOW);

        InstallmentReceivable third = item(view, fixture.third().id());
        assertThat(third.status()).isEqualTo("DUE");
        assertThat(third.lastDunningLevel()).isNull();
        assertThat(third.lastDunningAt()).isNull();
    }

    @Test
    void enforcesTheTenantWallOnCreateAndList() {
        Fixture fixture = seedFixture();

        assertNotFound(() -> dunningService.create(OTHER_TENANT, fixture.first().id(),
                new CreateDunningActionRequest("REMINDER", "EMAIL", null)));
        assertNotFound(() -> dunningService.list(OTHER_TENANT, fixture.first().id()));

        Policy otherPolicy = createPolicy(OTHER_TENANT, "POL-DUN-0200");
        PolicyBillingPlan otherPlan = createPlan(otherPolicy, "BP-DUN-0200");
        PolicyInstallment otherInstallment = insertInstallment(otherPlan, 1, TODAY.minusDays(2),
                "1000.0000", "DUE", null, null);
        assertNotFound(() -> dunningService.create(TENANT, otherInstallment.id(),
                new CreateDunningActionRequest("REMINDER", "EMAIL", null)));

        // The installment tenant disagrees with its plan/policy tenant, so it is
        // invisible to both tenants even though its own tenant column matches.
        Policy mismatchedPolicy = createPolicy(OTHER_TENANT, "POL-DUN-0201");
        PolicyBillingPlan mismatchedPlan = createPlan(mismatchedPolicy, "BP-DUN-0201");
        PolicyInstallment mismatched = insertInstallment(mismatchedPlan, 1, TODAY.minusDays(1),
                "1000.0000", "DUE", null, null, TENANT);
        assertNotFound(() -> dunningService.create(TENANT, mismatched.id(),
                new CreateDunningActionRequest("REMINDER", "EMAIL", null)));

        assertNotFound(() -> dunningService.create(TENANT, UUID.randomUUID(),
                new CreateDunningActionRequest("REMINDER", "EMAIL", null)));
        assertThat(count("billing_dunning_action")).isZero();
    }

    @Test
    void writesPolicyDunningSentEventInTheSameTransaction() {
        Fixture fixture = seedFixture();

        BillingDunningAction action = dunningService.create(TENANT, fixture.first().id(),
                new CreateDunningActionRequest("FIRST_NOTICE", "EMAIL", "Coverage lapse warning"));

        Map<String, Object> event = event(fixture.policy().id());
        int policyVersion = policyService.get(TENANT, fixture.policy().id()).version();
        assertThat(event.get("aggregate_type")).isEqualTo("Policy");
        assertThat(event.get("aggregate_version")).isEqualTo((long) policyVersion + 1L);
        assertThat(event.get("status")).isEqualTo("PENDING");
        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"policyId\":\"" + fixture.policy().id() + "\"")
                .contains("\"policyNumber\":\"POL-DUN-0001\"")
                .contains("\"installmentId\":\"" + fixture.first().id() + "\"")
                .contains("\"level\":\"FIRST_NOTICE\"")
                .contains("\"channel\":\"EMAIL\"")
                .contains("\"sentAt\":\"2026-06-15T12:00:00Z\"")
                .contains("\"status\":\"SENT\"")
                .doesNotContain("\"notes\"");
        assertThat((String) event.get("headers")).contains("\"book\":\"DIRECT\"");

        List<Map<String, Object>> actions = jdbc.queryForList("""
                SELECT sent_at, status FROM billing_dunning_action WHERE id = ?
                """, action.id());
        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().get("status")).isEqualTo("SENT");
    }

    @Test
    void rollbackLeavesNoActionNoEventAndNoVersionBump() {
        Fixture fixture = seedFixture();
        int versionBefore = policyService.get(TENANT, fixture.policy().id()).version();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            dunningService.create(TENANT, fixture.first().id(),
                    new CreateDunningActionRequest("SECOND_NOTICE", "LETTER", "force rollback"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("billing_dunning_action")).isZero();
        assertThat(countEvents()).isZero();
        assertThat(policyService.get(TENANT, fixture.policy().id()).version())
                .isEqualTo(versionBefore);
        assertThat(dunningService.list(TENANT, fixture.first().id())).isEmpty();
    }

    @Test
    void validatesTheDunningVocabularyAndNotesCap() {
        Fixture fixture = seedFixture();

        assertBadRequest(() -> dunningService.create(TENANT, fixture.first().id(),
                new CreateDunningActionRequest("FINAL_DEMAND", "EMAIL", null)),
                "INVALID_DUNNING_LEVEL", "level");
        assertBadRequest(() -> dunningService.create(TENANT, fixture.first().id(),
                new CreateDunningActionRequest("REMINDER", "FAX", null)),
                "INVALID_DUNNING_CHANNEL", "channel");
        assertBadRequest(() -> dunningService.create(TENANT, fixture.first().id(),
                new CreateDunningActionRequest("REMINDER", "EMAIL", "x".repeat(2001))),
                "INVALID_DUNNING_NOTES", "notes");

        BillingDunningAction maxNotes = dunningService.create(TENANT, fixture.second().id(),
                new CreateDunningActionRequest("REMINDER", "EMAIL", "x".repeat(2000)));
        assertThat(maxNotes.notes()).hasSize(2000);
    }

    private void assertNotDunnable(UUID installmentId, String expectedStatus) {
        assertThatThrownBy(() -> dunningService.create(TENANT, installmentId,
                new CreateDunningActionRequest("REMINDER", "EMAIL", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("INSTALLMENT_NOT_DUNNABLE");
                    assertThat(api.meta()).containsEntry("currentStatus", expectedStatus);
                });
    }

    private Fixture seedFixture() {
        Policy policy = createPolicy(TENANT, "POL-DUN-0001");
        PolicyBillingPlan plan = createPlan(policy, "BP-DUN-0001");
        PolicyInstallment first = insertInstallment(plan, 1, TODAY.minusDays(10),
                "1000.0000", "DUE", null, null);
        PolicyInstallment second = insertInstallment(plan, 2, TODAY.plusDays(10),
                "2000.0000", "DUE", null, null);
        PolicyInstallment third = insertInstallment(plan, 3, TODAY.plusDays(20),
                "3000.0000", "DUE", null, null);
        return new Fixture(policy, plan, first, second, third);
    }

    private Policy createPolicy(UUID tenantId, String policyNumber) {
        return policyService.create(new CreatePolicyRequest(tenantId, policyNumber, "CGL", PARTY,
                null, EFFECTIVE, EXPIRATION, new BigDecimal("120000.00"), "USD",
                new BigDecimal("5000000.00"), "USD"));
    }

    private PolicyBillingPlan createPlan(Policy policy, String planNumber) {
        PolicyBillingPlan plan = new PolicyBillingPlan(UUID.randomUUID(), policy.tenantId(),
                policy.id(), planNumber, new BigDecimal("6000.0000"), "USD", 3, "MONTHLY",
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

    private Map<String, Object> event(UUID policyId) {
        return jdbc.queryForMap("""
                SELECT aggregate_type, aggregate_version, status, payload, headers
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                """, policyId, EVENT_TYPE);
    }

    private static InstallmentReceivable item(ReceivablesView view, UUID installmentId) {
        return view.items().stream()
                .filter(item -> item.installmentId().equals(installmentId))
                .findFirst()
                .orElseThrow();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int countEvents() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ?", Integer.class,
                EVENT_TYPE);
    }

    private static void assertBadRequest(ThrowingCall call, String code, String field) {
        assertThatThrownBy(call::run).isInstanceOf(ApiException.class).satisfies(ex -> {
            ApiException api = (ApiException) ex;
            assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(api.code()).isEqualTo(code);
            assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                    .containsExactly(field);
        });
    }

    private static void assertNotFound(ThrowingCall call) {
        assertThatThrownBy(call::run).isInstanceOf(ApiException.class).satisfies(ex -> {
            ApiException api = (ApiException) ex;
            assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(api.code()).isEqualTo("INSTALLMENT_NOT_FOUND");
        });
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }

    private record Fixture(Policy policy, PolicyBillingPlan plan, PolicyInstallment first,
                           PolicyInstallment second, PolicyInstallment third) {
    }
}
