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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end credit note slice against H2: create/list ordering, the
 * DRAFT/ISSUED/APPLIED/CANCELLED graph, timestamps, validation, tenant wall,
 * outbox payloads, and transactional rollback.
 */
@SpringBootTest
@ActiveProfiles("test")
class CreditNoteServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);

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
                "policy_billing_plan", "billing_dunning_action", "policy_limit_erosion",
                "policy_limit_state", "policy_limit", "policy_coverage",
                "policy_endorsement", "policy_insured", "policy_location",
                "policy_cancellation", "policy_reinstatement", "policy_version",
                "policy_command_idempotency", "policy_idempotency", "policy")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void createsADraftCreditNoteForThePolicyCurrency() {
        Policy policy = createPolicy("POL-CN-0001", new BigDecimal("120000.00"));

        PolicyCreditNote note = creditNoteService.create(TENANT, policy.id(),
                new CreateCreditNoteRequest(new BigDecimal("1500.5"), "usd",
                        "  promotional adjustment  "));

        assertThat(note.id()).isNotNull();
        assertThat(note.tenantId()).isEqualTo(TENANT);
        assertThat(note.policyId()).isEqualTo(policy.id());
        assertThat(note.noteNumber()).matches("CN-\\d{7}");
        assertThat(note.amount()).isEqualByComparingTo("1500.5000");
        assertThat(note.currency()).isEqualTo("USD");
        assertThat(note.reason()).isEqualTo("promotional adjustment");
        assertThat(note.status()).isEqualTo("DRAFT");
        assertThat(note.issuedAt()).isNull();
        assertThat(note.appliedAt()).isNull();
        assertThat(note.createdAt()).isNotNull();
        assertThat(creditNoteService.list(TENANT, policy.id())).containsExactly(note);
        assertThat(policyService.get(TENANT, policy.id()).version())
                .isEqualTo(policy.version() + 1);
        assertThat(countEvents("policy.credit-note.issued")).isZero();
        assertThat(countEvents("policy.credit-note.applied")).isZero();
        assertThat(countEvents("policy.credit-note.cancelled")).isZero();
    }

    @Test
    void listsCreditNotesNewestFirst() {
        Policy policy = createPolicy("POL-CN-0002", new BigDecimal("120000.00"));
        PolicyCreditNote first = createNote(policy, "100.00");
        PolicyCreditNote second = createNote(policy, "200.00");
        PolicyCreditNote third = createNote(policy, "300.00");

        assertThat(creditNoteService.list(TENANT, policy.id()))
                .extracting(PolicyCreditNote::noteNumber)
                .containsExactly(third.noteNumber(), second.noteNumber(), first.noteNumber());
    }

    @Test
    void issuingStampsIssuedAtAndWritesTheIssuedEvent() {
        Policy policy = createPolicy("POL-CN-0003", new BigDecimal("120000.00"));
        PolicyCreditNote note = createNote(policy, "250.00");

        PolicyCreditNote issued = creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("issued"));

        assertThat(issued.status()).isEqualTo("ISSUED");
        assertThat(issued.issuedAt()).isNotNull();
        assertThat(issued.appliedAt()).isNull();
        assertThat(issued.noteNumber()).isEqualTo(note.noteNumber());

        Map<String, Object> event = event("policy.credit-note.issued", policy.id());
        assertThat(event.get("aggregate_type")).isEqualTo("Policy");
        assertThat(event.get("aggregate_version")).isEqualTo(3L);
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat((String) event.get("payload"))
                .contains("\"policyId\":\"" + policy.id() + "\"")
                .contains("\"policyNumber\":\"POL-CN-0003\"")
                .contains("\"creditNoteId\":\"" + note.id() + "\"")
                .contains("\"noteNumber\":\"" + note.noteNumber() + "\"")
                .contains("\"amount\":\"250.0000\"")
                .contains("\"currency\":\"USD\"")
                .contains("\"status\":\"ISSUED\"");
        assertThat((String) event.get("headers")).contains("\"book\":\"DIRECT\"");
    }

    @Test
    void applyingPreservesIssuedAtAndStampsAppliedAt() {
        Policy policy = createPolicy("POL-CN-0004", new BigDecimal("120000.00"));
        PolicyCreditNote note = createNote(policy, "250.00");
        PolicyCreditNote issued = creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("ISSUED"));

        PolicyCreditNote applied = creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("APPLIED"));

        assertThat(applied.status()).isEqualTo("APPLIED");
        assertThat(applied.issuedAt()).isEqualTo(issued.issuedAt());
        assertThat(applied.appliedAt()).isNotNull();
        assertThat(applied.appliedAt()).isAfterOrEqualTo(applied.issuedAt());
        String payload = (String) event("policy.credit-note.applied", policy.id()).get("payload");
        assertThat(payload)
                .contains("\"creditNoteId\":\"" + note.id() + "\"")
                .contains("\"status\":\"APPLIED\"");
    }

    @Test
    void cancellingADraftSetsNoTimestamp() {
        Policy policy = createPolicy("POL-CN-0005", new BigDecimal("120000.00"));
        PolicyCreditNote note = createNote(policy, "250.00");

        PolicyCreditNote cancelled = creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("CANCELLED"));

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.issuedAt()).isNull();
        assertThat(cancelled.appliedAt()).isNull();
        String payload =
                (String) event("policy.credit-note.cancelled", policy.id()).get("payload");
        assertThat(payload)
                .contains("\"creditNoteId\":\"" + note.id() + "\"")
                .contains("\"status\":\"CANCELLED\"");
    }

    @Test
    void cancellingAnIssuedNotePreservesIssuedAt() {
        Policy policy = createPolicy("POL-CN-0006", new BigDecimal("120000.00"));
        PolicyCreditNote note = createNote(policy, "250.00");
        PolicyCreditNote issued = creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("ISSUED"));

        PolicyCreditNote cancelled = creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("CANCELLED"));

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.issuedAt()).isEqualTo(issued.issuedAt());
        assertThat(cancelled.appliedAt()).isNull();
    }

    @Test
    void rejectsDisallowedAndRepeatedTransitions() {
        Policy policy = createPolicy("POL-CN-0007", new BigDecimal("120000.00"));
        PolicyCreditNote note = createNote(policy, "250.00");

        assertConflict(() -> creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("APPLIED")),
                "INVALID_CREDIT_NOTE_TRANSITION");
        assertBadRequest(() -> creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("DRAFT")), "INVALID_CREDIT_NOTE_STATUS");
        assertBadRequest(() -> creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("VOID")), "INVALID_CREDIT_NOTE_STATUS");

        creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("ISSUED"));
        assertConflict(() -> creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("ISSUED")),
                "INVALID_CREDIT_NOTE_TRANSITION");

        creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("APPLIED"));
        assertConflict(() -> creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("CANCELLED")),
                "INVALID_CREDIT_NOTE_TRANSITION");
        assertThat(creditNoteService.list(TENANT, policy.id()).getFirst().status())
                .isEqualTo("APPLIED");
        assertThat(countEvents("policy.credit-note.applied")).isEqualTo(1);
        assertThat(countEvents("policy.credit-note.cancelled")).isZero();
    }

    @Test
    void aCancelledNoteIsTerminal() {
        Policy policy = createPolicy("POL-CN-0008", new BigDecimal("120000.00"));
        PolicyCreditNote note = createNote(policy, "250.00");
        creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("CANCELLED"));

        assertConflict(() -> creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("ISSUED")),
                "INVALID_CREDIT_NOTE_TRANSITION");
        assertThat(creditNoteService.list(TENANT, policy.id()).getFirst().status())
                .isEqualTo("CANCELLED");
    }

    @Test
    void validatesAmountCurrencyAndReason() {
        Policy policy = createPolicy("POL-CN-0009", new BigDecimal("120000.00"));

        assertBadRequest(() -> creditNoteService.create(TENANT, policy.id(),
                new CreateCreditNoteRequest(BigDecimal.ZERO, "USD", null)),
                "INVALID_CREDIT_NOTE_AMOUNT");
        assertBadRequest(() -> creditNoteService.create(TENANT, policy.id(),
                new CreateCreditNoteRequest(new BigDecimal("-1.00"), "USD", null)),
                "INVALID_CREDIT_NOTE_AMOUNT");
        assertBadRequest(() -> creditNoteService.create(TENANT, policy.id(),
                new CreateCreditNoteRequest(null, "USD", null)),
                "INVALID_CREDIT_NOTE_AMOUNT");
        assertBadRequest(() -> creditNoteService.create(TENANT, policy.id(),
                new CreateCreditNoteRequest(BigDecimal.ONE, "EUR", null)),
                "CURRENCY_MISMATCH");
        assertBadRequest(() -> creditNoteService.create(TENANT, policy.id(),
                new CreateCreditNoteRequest(BigDecimal.ONE, "US", null)),
                "CURRENCY_MISMATCH");
        assertBadRequest(() -> creditNoteService.create(TENANT, policy.id(),
                new CreateCreditNoteRequest(BigDecimal.ONE, "USD", "x".repeat(501))),
                "INVALID_CREDIT_NOTE_REASON");

        PolicyCreditNote atLimit = creditNoteService.create(TENANT, policy.id(),
                new CreateCreditNoteRequest(BigDecimal.ONE, "usd", "x".repeat(500)));
        assertThat(atLimit.reason()).hasSize(500);
        assertThat(atLimit.currency()).isEqualTo("USD");

        PolicyCreditNote blankReason = creditNoteService.create(TENANT, policy.id(),
                new CreateCreditNoteRequest(BigDecimal.ONE, "USD", "   "));
        assertThat(blankReason.reason()).isNull();
        assertThat(count("policy_credit_note")).isEqualTo(2);
    }

    @Test
    void enforcesTheTenantAndPolicyWall() {
        Policy policy = createPolicy("POL-CN-0010", new BigDecimal("120000.00"));
        PolicyCreditNote note = createNote(policy, "250.00");

        assertNotFound(() -> creditNoteService.list(OTHER_TENANT, policy.id()));
        assertNotFound(() -> creditNoteService.create(OTHER_TENANT, policy.id(),
                new CreateCreditNoteRequest(BigDecimal.ONE, "USD", null)));
        assertNotFound(() -> creditNoteService.updateStatus(OTHER_TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("ISSUED")));

        Policy otherPolicy = createPolicy("POL-CN-0011", new BigDecimal("90000.00"));
        assertNotFound(() -> creditNoteService.updateStatus(TENANT, otherPolicy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("ISSUED")));
        assertThat(creditNoteService.list(TENANT, policy.id()).getFirst().status())
                .isEqualTo("DRAFT");
    }

    @Test
    void writesOneDistinctEventPerAcceptedTransition() {
        Policy policy = createPolicy("POL-CN-0012", new BigDecimal("120000.00"));
        PolicyCreditNote first = createNote(policy, "100.00");
        PolicyCreditNote second = createNote(policy, "200.00");
        creditNoteService.updateStatus(TENANT, policy.id(), first.id(),
                new UpdateCreditNoteStatusRequest("ISSUED"));
        creditNoteService.updateStatus(TENANT, policy.id(), second.id(),
                new UpdateCreditNoteStatusRequest("CANCELLED"));
        creditNoteService.updateStatus(TENANT, policy.id(), first.id(),
                new UpdateCreditNoteStatusRequest("APPLIED"));

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, event_type, aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type LIKE 'policy.credit-note.%'
                 ORDER BY event_sequence
                """, policy.id());
        assertThat(rows).extracting(row -> row.get("event_type")).containsExactly(
                "policy.credit-note.issued",
                "policy.credit-note.cancelled",
                "policy.credit-note.applied");
        assertThat(rows).extracting(row -> row.get("aggregate_version"))
                .doesNotHaveDuplicates();
        for (Map<String, Object> row : rows) {
            String payload = (String) row.get("payload");
            assertThat(payload)
                    .contains("\"policyId\":\"" + policy.id() + "\"")
                    .contains("\"policyNumber\":\"POL-CN-0012\"")
                    .contains("\"creditNoteId\":")
                    .contains("\"noteNumber\":\"CN-")
                    .contains("\"amount\":")
                    .contains("\"currency\":\"USD\"")
                    .contains("\"status\":");
            OutboxEvent stored = outboxRepository.findById((UUID) row.get("id")).orElseThrow();
            EventEnvelope envelope = stored.toEnvelope();
            assertThat(envelope.book()).isEqualTo(Book.DIRECT);
            assertThat(envelope.aggregateId()).isEqualTo(policy.id());
            assertThat(envelope.aggregateVersion()).isGreaterThanOrEqualTo(1L);
        }
    }

    @Test
    void createRollbackLeavesNoNotePolicyChangeOrEvent() {
        Policy policy = createPolicy("POL-CN-0013", new BigDecimal("120000.00"));
        int versionBefore = policy.version();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            creditNoteService.create(TENANT, policy.id(),
                    new CreateCreditNoteRequest(new BigDecimal("100.00"), "USD", "rollback"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("policy_credit_note")).isZero();
        assertThat(countEvents("policy.credit-note.issued")).isZero();
        assertThat(policyService.get(TENANT, policy.id()).version()).isEqualTo(versionBefore);
    }

    @Test
    void transitionRollbackLeavesTheNoteDraftAndWritesNoEvent() {
        Policy policy = createPolicy("POL-CN-0014", new BigDecimal("120000.00"));
        PolicyCreditNote note = createNote(policy, "100.00");
        int versionBefore = policyService.get(TENANT, policy.id()).version();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                    new UpdateCreditNoteStatusRequest("ISSUED"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        PolicyCreditNote stored = creditNoteService.list(TENANT, policy.id()).getFirst();
        assertThat(stored.status()).isEqualTo("DRAFT");
        assertThat(stored.issuedAt()).isNull();
        assertThat(countEvents("policy.credit-note.issued")).isZero();
        assertThat(policyService.get(TENANT, policy.id()).version()).isEqualTo(versionBefore);
    }

    private Policy createPolicy(String number, BigDecimal premium) {
        return policyService.create(new CreatePolicyRequest(TENANT, number, "CGL", PARTY, null,
                EFFECTIVE, EXPIRATION, premium, "USD", new BigDecimal("5000000.00"), "USD"));
    }

    private PolicyCreditNote createNote(Policy policy, String amount) {
        return creditNoteService.create(TENANT, policy.id(),
                new CreateCreditNoteRequest(new BigDecimal(amount), "USD", "adjustment"));
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

    private static void assertBadRequest(ThrowingCall call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(ApiException.class).satisfies(ex -> {
            ApiException api = (ApiException) ex;
            assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(api.code()).isEqualTo(code);
        });
    }

    private static void assertConflict(ThrowingCall call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(ApiException.class).satisfies(ex -> {
            ApiException api = (ApiException) ex;
            assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
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
