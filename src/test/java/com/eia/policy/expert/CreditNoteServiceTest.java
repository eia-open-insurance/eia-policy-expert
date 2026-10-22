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
import java.sql.Timestamp;
import java.time.Clock;
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
 * outbox payloads, transactional rollback, and cancellation return-premium
 * links (amount/currency checks, duplicate arbitration, and event payload).
 */
@SpringBootTest
@ActiveProfiles("test")
class CreditNoteServiceTest extends ProductCatalogTestSupport {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);
    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 2, 1);

    @Autowired
    private CreditNoteService creditNoteService;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private BillingService billingService;

    @Autowired
    private CancellationService cancellationService;

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
        assertThat(note.cancellationId()).isNull();
        assertThat(note.cancellationNumber()).isNull();
        assertThat(creditNoteService.list(TENANT, policy.id())).containsExactly(note);
        assertThat(policyService.get(TENANT, policy.id()).version())
                .isEqualTo(policy.version() + 1);
        assertThat(countEvents("policy.credit-note.created")).isEqualTo(1);
        assertThat(countEvents("policy.credit-note.issued")).isZero();
        assertThat(countEvents("policy.credit-note.applied")).isZero();
        assertThat(countEvents("policy.credit-note.cancelled")).isZero();

        String payload =
                (String) event("policy.credit-note.created", policy.id()).get("payload");
        assertThat(payload)
                .contains("\"creditNoteId\":\"" + note.id() + "\"")
                .contains("\"status\":\"DRAFT\"")
                .doesNotContain("cancellationId")
                .doesNotContain("cancellationNumber");
    }

    @Test
    void createsAReturnPremiumCreditNoteLinkedToTheCancellation() {
        PolicyCancellation cancellation = createCancellation("POL-CN-LINK-0001");

        PolicyCreditNote note = creditNoteService.create(TENANT, cancellation.policyId(),
                new CreateCreditNoteRequest(cancellation.returnPremiumAmount(), "usd",
                        "cancellation return premium", cancellation.id()));

        assertThat(note.status()).isEqualTo("DRAFT");
        assertThat(note.cancellationId()).isEqualTo(cancellation.id());
        assertThat(note.cancellationNumber()).isEqualTo(cancellation.cancellationNumber());
        assertThat(note.cancellationNumber()).matches("CX-\\d{7}");
        assertThat(note.amount()).isEqualByComparingTo(cancellation.returnPremiumAmount());
        assertThat(note.currency()).isEqualTo(cancellation.premiumCurrency());

        UUID storedCancellationId = jdbc.queryForObject(
                "SELECT cancellation_id FROM policy_credit_note WHERE id = ?",
                UUID.class, note.id());
        assertThat(storedCancellationId).isEqualTo(cancellation.id());

        String payload = (String) event("policy.credit-note.created", cancellation.policyId())
                .get("payload");
        assertThat(payload)
                .contains("\"creditNoteId\":\"" + note.id() + "\"")
                .contains("\"status\":\"DRAFT\"")
                .contains("\"cancellationId\":\"" + cancellation.id() + "\"")
                .contains("\"cancellationNumber\":\"" + cancellation.cancellationNumber() + "\"");
    }

    @Test
    void fallsBackToTheCancellationIdForALegacyCancellationWithoutANumber() {
        PolicyCancellation cancellation = createCancellation("POL-CN-LINK-0008");
        jdbc.update("UPDATE policy_cancellation SET cancellation_number = NULL WHERE id = ?",
                cancellation.id());

        PolicyCreditNote note = creditNoteService.create(TENANT, cancellation.policyId(),
                new CreateCreditNoteRequest(cancellation.returnPremiumAmount(), "USD",
                        "legacy cancellation refund", cancellation.id()));

        assertThat(note.cancellationId()).isEqualTo(cancellation.id());
        assertThat(note.cancellationNumber()).isEqualTo(cancellation.id().toString());
        assertThat(creditNoteService.list(TENANT, cancellation.policyId()).getFirst()
                .cancellationNumber()).isEqualTo(cancellation.id().toString());
    }

    @Test
    void rejectsADuplicateCreditNoteForTheSameCancellation() {
        PolicyCancellation cancellation = createCancellation("POL-CN-LINK-0002");
        CreateCreditNoteRequest request = new CreateCreditNoteRequest(
                cancellation.returnPremiumAmount(), "USD", "cancellation return premium",
                cancellation.id());
        PolicyCreditNote first = creditNoteService.create(TENANT, cancellation.policyId(),
                request);

        // The duplicate link wins even when the retried body is different: the
        // cancellation is already fully refunded.
        assertThatThrownBy(() -> creditNoteService.create(TENANT, cancellation.policyId(),
                new CreateCreditNoteRequest(cancellation.returnPremiumAmount()
                        .add(BigDecimal.ONE), "USD", "different body", cancellation.id())))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("DUPLICATE_CANCELLATION_CREDIT_NOTE");
                    assertThat(api.meta())
                            .containsEntry("cancellationId", cancellation.id().toString())
                            .containsEntry("creditNoteId", first.id().toString());
                });

        assertThatThrownBy(() -> creditNoteService.create(TENANT, cancellation.policyId(),
                request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("DUPLICATE_CANCELLATION_CREDIT_NOTE");
                });

        assertThat(creditNoteService.list(TENANT, cancellation.policyId())).hasSize(1);
        assertThat(countEvents("policy.credit-note.created")).isEqualTo(1);
    }

    @Test
    void linkedCreateRollbackLeavesNoNoteLinkOrEvent() {
        PolicyCancellation cancellation = createCancellation("POL-CN-LINK-0009");
        int versionBefore = policyService.get(TENANT, cancellation.policyId()).version();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            creditNoteService.create(TENANT, cancellation.policyId(),
                    new CreateCreditNoteRequest(cancellation.returnPremiumAmount(), "USD",
                            "rollback", cancellation.id()));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(creditNoteService.list(TENANT, cancellation.policyId())).isEmpty();
        assertThat(countEvents("policy.credit-note.created")).isZero();
        assertThat(policyService.get(TENANT, cancellation.policyId()).version())
                .isEqualTo(versionBefore);
    }

    @Test
    void rejectsAnAmountThatDoesNotMatchTheCancellationReturnPremium() {
        PolicyCancellation cancellation = createCancellation("POL-CN-LINK-0003");
        BigDecimal twoCentsOver = cancellation.returnPremiumAmount()
                .add(new BigDecimal("0.02"));

        assertBadRequest(() -> creditNoteService.create(TENANT, cancellation.policyId(),
                        new CreateCreditNoteRequest(twoCentsOver, "USD", "cancellation refund",
                                cancellation.id())),
                "CREDIT_NOTE_AMOUNT_MISMATCH");
        assertThat(creditNoteService.list(TENANT, cancellation.policyId())).isEmpty();
        assertThat(countEvents("policy.credit-note.created")).isZero();

        PolicyCreditNote withinTolerance = creditNoteService.create(TENANT,
                cancellation.policyId(),
                new CreateCreditNoteRequest(cancellation.returnPremiumAmount()
                        .add(new BigDecimal("0.01")), "USD", "cancellation refund",
                        cancellation.id()));
        assertThat(withinTolerance.amount()).isEqualByComparingTo(
                cancellation.returnPremiumAmount().add(new BigDecimal("0.01")));
    }

    @Test
    void rejectsACurrencyThatDoesNotMatchTheCancellation() {
        PolicyCancellation cancellation = createCancellation("POL-CN-LINK-0004");
        // Simulate a legacy cancellation whose premium currency differs from
        // the policy currency, so only the cancellation-level comparison fails.
        jdbc.update("UPDATE policy_cancellation SET premium_currency = 'EUR' WHERE id = ?",
                cancellation.id());

        assertBadRequest(() -> creditNoteService.create(TENANT, cancellation.policyId(),
                        new CreateCreditNoteRequest(cancellation.returnPremiumAmount(), "USD",
                                "cancellation refund", cancellation.id())),
                "CURRENCY_MISMATCH");
        assertThat(creditNoteService.list(TENANT, cancellation.policyId())).isEmpty();
    }

    @Test
    void rejectsAnInactiveCancellation() {
        PolicyCancellation cancellation = createCancellation("POL-CN-LINK-0005");
        jdbc.update("UPDATE policy_cancellation SET status = 'REINSTATED' WHERE id = ?",
                cancellation.id());

        assertThatThrownBy(() -> creditNoteService.create(TENANT, cancellation.policyId(),
                new CreateCreditNoteRequest(cancellation.returnPremiumAmount(), "USD",
                        "cancellation refund", cancellation.id())))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("CANCELLATION_NOT_ACTIVE");
                    assertThat(api.meta())
                            .containsEntry("currentStatus", "REINSTATED");
                });
        assertThat(countEvents("policy.credit-note.created")).isZero();
    }

    @Test
    void rejectsACancellationFromAnotherPolicyTenantOrAnUnknownId() {
        PolicyCancellation cancellation = createCancellation("POL-CN-LINK-0006");
        Policy otherPolicy = createPolicy("POL-CN-LINK-0007", new BigDecimal("90000.00"));

        assertNotFound(() -> creditNoteService.create(TENANT, otherPolicy.id(),
                new CreateCreditNoteRequest(cancellation.returnPremiumAmount(), "USD",
                        "wrong policy", cancellation.id())));
        assertNotFound(() -> creditNoteService.create(OTHER_TENANT, cancellation.policyId(),
                new CreateCreditNoteRequest(cancellation.returnPremiumAmount(), "USD",
                        "wrong tenant", cancellation.id())));
        assertNotFound(() -> creditNoteService.create(TENANT, cancellation.policyId(),
                new CreateCreditNoteRequest(cancellation.returnPremiumAmount(), "USD",
                        "unknown cancellation", UUID.randomUUID())));

        assertThat(creditNoteService.list(TENANT, cancellation.policyId())).isEmpty();
        assertThat(countEvents("policy.credit-note.created")).isZero();
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
        PolicyInstallment installment = createPlan(policy).installments().getFirst();
        PolicyCreditNote note = createNote(policy, "250.00");
        PolicyCreditNote issued = issue(policy, note);

        PolicyCreditNote applied = apply(policy, note, installment.id());

        assertThat(applied.status()).isEqualTo("APPLIED");
        assertThat(applied.issuedAt()).isEqualTo(issued.issuedAt());
        assertThat(applied.appliedAt()).isNotNull();
        assertThat(applied.appliedAt()).isAfterOrEqualTo(applied.issuedAt());
        assertThat(applied.appliedInstallmentId()).isEqualTo(installment.id());

        PolicyInstallment credited = billingService.get(TENANT, policy.id()).installments()
                .getFirst();
        assertThat(credited.status()).isEqualTo("DUE");
        assertThat(credited.creditedAmount()).isEqualByComparingTo("250.0000");
        assertThat(credited.outstandingAmount()).isEqualByComparingTo("39750.0000");

        String payload = (String) event("policy.credit-note.applied", policy.id()).get("payload");
        assertThat(payload)
                .contains("\"creditNoteId\":\"" + note.id() + "\"")
                .contains("\"installmentId\":\"" + installment.id() + "\"")
                .contains("\"creditedAmount\":\"250.0000\"")
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
        PolicyInstallment installment = createPlan(policy).installments().getFirst();
        PolicyCreditNote note = createNote(policy, "250.00");

        assertConflict(() -> creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("APPLIED")),
                "INVALID_CREDIT_NOTE_TRANSITION");
        assertBadRequest(() -> creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("DRAFT")), "INVALID_CREDIT_NOTE_STATUS");
        assertBadRequest(() -> creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("VOID")), "INVALID_CREDIT_NOTE_STATUS");

        issue(policy, note);
        assertConflict(() -> creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("ISSUED")),
                "INVALID_CREDIT_NOTE_TRANSITION");

        apply(policy, note, installment.id());
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
        PolicyInstallment installment = createPlan(policy).installments().getFirst();
        PolicyCreditNote first = createNote(policy, "100.00");
        PolicyCreditNote second = createNote(policy, "200.00");
        issue(policy, first);
        creditNoteService.updateStatus(TENANT, policy.id(), second.id(),
                new UpdateCreditNoteStatusRequest("CANCELLED"));
        apply(policy, first, installment.id());

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, event_type, aggregate_version, payload
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type LIKE 'policy.credit-note.%'
                 ORDER BY event_sequence
                """, policy.id());
        assertThat(rows).extracting(row -> row.get("event_type")).containsExactly(
                "policy.credit-note.created",
                "policy.credit-note.created",
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
        assertThat(countEvents("policy.credit-note.created")).isZero();
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

    @Test
    void appliesAFullCreditAndMarksTheInstallmentCredited() {
        Policy policy = createPolicy("POL-CN-0015", new BigDecimal("120000.00"));
        PolicyInstallment installment = createPlan(policy).installments().getFirst();
        PolicyCreditNote note = createNote(policy, "40000.00");
        issue(policy, note);

        PolicyCreditNote applied = apply(policy, note, installment.id());

        assertThat(applied.status()).isEqualTo("APPLIED");
        assertThat(applied.appliedInstallmentId()).isEqualTo(installment.id());

        PolicyInstallment credited = billingService.get(TENANT, policy.id()).installments()
                .getFirst();
        assertThat(credited.status()).isEqualTo("CREDITED");
        assertThat(credited.creditedAmount()).isEqualByComparingTo("40000.0000");
        assertThat(credited.outstandingAmount()).isEqualByComparingTo("0.0000");
        assertThat(credited.paidAt()).isNull();

        BillingPlanSummary summary = billingService.get(TENANT, policy.id()).summary();
        assertThat(summary.totalAmount()).isEqualByComparingTo("120000.0000");
        assertThat(summary.paidAmount()).isEqualByComparingTo("0.0000");
        assertThat(summary.waivedAmount()).isEqualByComparingTo("0.0000");
        assertThat(summary.creditedAmount()).isEqualByComparingTo("40000.0000");
        assertThat(summary.outstandingAmount()).isEqualByComparingTo("80000.0000");

        assertConflict(() -> apply(policy, note, installment.id()),
                "INVALID_CREDIT_NOTE_TRANSITION");
    }

    @Test
    void appliesPartialCreditsAndKeepsTheInstallmentOpen() {
        Policy policy = createPolicy("POL-CN-0016", new BigDecimal("120000.00"));
        PolicyInstallment installment = createPlan(policy).installments().getFirst();
        PolicyCreditNote first = createNote(policy, "10000.00");
        PolicyCreditNote second = createNote(policy, "5000.00");
        issue(policy, first);
        issue(policy, second);

        apply(policy, first, installment.id());
        PolicyInstallment partial = billingService.get(TENANT, policy.id()).installments()
                .getFirst();
        assertThat(partial.status()).isEqualTo("DUE");
        assertThat(partial.creditedAmount()).isEqualByComparingTo("10000.0000");
        assertThat(partial.outstandingAmount()).isEqualByComparingTo("30000.0000");

        apply(policy, second, installment.id());
        PolicyInstallment after = billingService.get(TENANT, policy.id()).installments()
                .getFirst();
        assertThat(after.status()).isEqualTo("DUE");
        assertThat(after.creditedAmount()).isEqualByComparingTo("15000.0000");
        assertThat(after.outstandingAmount()).isEqualByComparingTo("25000.0000");

        String payload = (String) latestEvent("policy.credit-note.applied", policy.id())
                .get("payload");
        assertThat(payload)
                .contains("\"installmentId\":\"" + installment.id() + "\"")
                .contains("\"creditedAmount\":\"15000.0000\"");
    }

    @Test
    void rejectsAnOverpaymentWithoutChangingTheNoteOrInstallment() {
        Policy policy = createPolicy("POL-CN-0017", new BigDecimal("120000.00"));
        PolicyInstallment installment = createPlan(policy).installments().getFirst();
        PolicyCreditNote note = createNote(policy, "40000.01");
        issue(policy, note);

        assertThatThrownBy(() -> apply(policy, note, installment.id()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("CREDIT_NOTE_EXCEEDS_INSTALLMENT");
                    assertThat(api.meta())
                            .containsEntry("availableAmount", "40000.0000")
                            .containsEntry("noteAmount", "40000.0100");
                });

        PolicyCreditNote unchanged = creditNoteService.list(TENANT, policy.id()).getFirst();
        assertThat(unchanged.status()).isEqualTo("ISSUED");
        assertThat(unchanged.appliedInstallmentId()).isNull();
        PolicyInstallment unchangedInstallment = billingService.get(TENANT, policy.id())
                .installments().getFirst();
        assertThat(unchangedInstallment.status()).isEqualTo("DUE");
        assertThat(unchangedInstallment.creditedAmount()).isEqualByComparingTo("0.0000");
        assertThat(countEvents("policy.credit-note.applied")).isZero();
    }

    @Test
    void rejectsACreditAgainstASettledOrCreditedInstallment() {
        Policy policy = createPolicy("POL-CN-0018", new BigDecimal("120000.00"));
        BillingPlanView plan = createPlan(policy);
        PolicyInstallment paidRow = plan.installments().get(0);
        PolicyInstallment waivedRow = plan.installments().get(1);
        billingService.updateInstallment(TENANT, policy.id(), paidRow.id(),
                new UpdateInstallmentRequest("PAID", UUID.randomUUID()));
        billingService.updateInstallment(TENANT, policy.id(), waivedRow.id(),
                new UpdateInstallmentRequest("WAIVED", null));

        PolicyCreditNote paidNote = createNote(policy, "100.00");
        PolicyCreditNote waivedNote = createNote(policy, "100.00");
        issue(policy, paidNote);
        issue(policy, waivedNote);

        assertConflict(() -> apply(policy, paidNote, paidRow.id()),
                "INSTALLMENT_NOT_CREDITABLE");
        assertConflict(() -> apply(policy, waivedNote, waivedRow.id()),
                "INSTALLMENT_NOT_CREDITABLE");
        assertThat(creditNoteService.list(TENANT, policy.id()))
                .extracting(PolicyCreditNote::status)
                .containsOnly("ISSUED");
        assertThat(countEvents("policy.credit-note.applied")).isZero();
    }

    @Test
    void rejectsAnInstallmentFromAnotherPolicyOrTenant() {
        Policy policy = createPolicy("POL-CN-0019", new BigDecimal("120000.00"));
        Policy otherPolicy = createPolicy("POL-CN-0020", new BigDecimal("90000.00"));
        PolicyInstallment otherInstallment = createPlan(otherPolicy).installments().getFirst();
        PolicyCreditNote note = createNote(policy, "100.00");
        issue(policy, note);

        assertNotFound(() -> apply(policy, note, otherInstallment.id()));
        assertNotFound(() -> creditNoteService.updateStatus(OTHER_TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("APPLIED", otherInstallment.id())));
        assertThat(creditNoteService.list(TENANT, policy.id()).getFirst().status())
                .isEqualTo("ISSUED");
    }

    @Test
    void requiresAnInstallmentIdWhenApplying() {
        Policy policy = createPolicy("POL-CN-0021", new BigDecimal("120000.00"));
        createPlan(policy);
        PolicyCreditNote note = createNote(policy, "100.00");
        issue(policy, note);

        assertBadRequest(() -> creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                        new UpdateCreditNoteStatusRequest("APPLIED")),
                "INVALID_CREDIT_NOTE_INSTALLMENT");
        assertThat(creditNoteService.list(TENANT, policy.id()).getFirst().status())
                .isEqualTo("ISSUED");
    }

    @Test
    void applyingRollsBackTheNoteAndInstallmentOnFailure() {
        Policy policy = createPolicy("POL-CN-0022", new BigDecimal("120000.00"));
        PolicyInstallment installment = createPlan(policy).installments().getFirst();
        PolicyCreditNote note = createNote(policy, "100.00");
        issue(policy, note);
        int versionBefore = policyService.get(TENANT, policy.id()).version();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                    new UpdateCreditNoteStatusRequest("APPLIED", installment.id()));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(creditNoteService.list(TENANT, policy.id()).getFirst().status())
                .isEqualTo("ISSUED");
        PolicyInstallment stored = billingService.get(TENANT, policy.id()).installments()
                .getFirst();
        assertThat(stored.creditedAmount()).isEqualByComparingTo("0.0000");
        assertThat(stored.status()).isEqualTo("DUE");
        assertThat(countEvents("policy.credit-note.applied")).isZero();
        assertThat(policyService.get(TENANT, policy.id()).version()).isEqualTo(versionBefore);
    }

    @Test
    void reversingAFullCreditRestoresTheInstallmentAndTheSummary() {
        Policy policy = createPolicy("POL-CN-0023", new BigDecimal("120000.00"));
        PolicyInstallment installment = createPlan(policy, today().plusDays(10))
                .installments().getFirst();
        PolicyCreditNote note = createNote(policy, "40000.00");
        PolicyCreditNote issued = issue(policy, note);
        PolicyCreditNote applied = apply(policy, note, installment.id());
        assertThat(applied.status()).isEqualTo("APPLIED");

        BillingPlanSummary before = billingService.get(TENANT, policy.id()).summary();
        assertThat(before.creditedAmount()).isEqualByComparingTo("40000.0000");
        assertThat(before.outstandingAmount()).isEqualByComparingTo("80000.0000");
        assertThat(billingService.listReceivables(TENANT, null, null, 100).items())
                .extracting(InstallmentReceivable::installmentId)
                .doesNotContain(installment.id());

        PolicyCreditNote reversed = creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("REVERSED", null, "  customer dispute  "));

        assertThat(reversed.status()).isEqualTo("REVERSED");
        assertThat(reversed.appliedInstallmentId()).isEqualTo(installment.id());
        assertThat(reversed.issuedAt()).isEqualTo(issued.issuedAt());
        assertThat(reversed.appliedAt()).isEqualTo(applied.appliedAt());
        assertThat(reversed.reversedAt()).isNotNull();
        assertThat(reversed.reversalReason()).isEqualTo("customer dispute");

        PolicyInstallment restored = billingService.get(TENANT, policy.id()).installments()
                .getFirst();
        assertThat(restored.status()).isEqualTo("DUE");
        assertThat(restored.creditedAmount()).isEqualByComparingTo("0.0000");
        assertThat(restored.outstandingAmount()).isEqualByComparingTo("40000.0000");

        BillingPlanSummary after = billingService.get(TENANT, policy.id()).summary();
        assertThat(after.creditedAmount()).isEqualByComparingTo("0.0000");
        assertThat(after.outstandingAmount()).isEqualByComparingTo("120000.0000");
        assertThat(after.overdueAmount()).isEqualByComparingTo("0.0000");

        ReceivablesView receivables = billingService.listReceivables(TENANT, null, null, 100);
        assertThat(receivables.items()).extracting(InstallmentReceivable::installmentId)
                .contains(installment.id());
        assertThat(receivables.totalAmount()).isEqualByComparingTo("120000.0000");
    }

    @Test
    void reversingAFullCreditOnAPastDueInstallmentRestoresOverdue() {
        Policy policy = createPolicy("POL-CN-0024", new BigDecimal("120000.00"));
        PolicyInstallment installment = createPlan(policy, today().minusDays(5))
                .installments().getFirst();
        PolicyCreditNote note = createNote(policy, "40000.00");
        issue(policy, note);
        apply(policy, note, installment.id());
        assertThat(storedInstallmentStatus(installment.id())).isEqualTo("CREDITED");

        reverse(policy, note, "billing correction");

        PolicyInstallment restored = billingService.get(TENANT, policy.id()).installments()
                .getFirst();
        assertThat(restored.status()).isEqualTo("OVERDUE");
        assertThat(restored.creditedAmount()).isEqualByComparingTo("0.0000");
        assertThat(restored.outstandingAmount()).isEqualByComparingTo("40000.0000");

        ReceivablesView overdue = billingService.listReceivables(TENANT, "OVERDUE", null, 100);
        assertThat(overdue.items()).extracting(InstallmentReceivable::installmentId)
                .containsExactly(installment.id());
        InstallmentReceivable item = overdue.items().getFirst();
        assertThat(item.status()).isEqualTo("OVERDUE");
        assertThat(item.daysPastDue()).isEqualTo(5L);
        assertThat(item.outstandingAmount()).isEqualByComparingTo("40000.0000");
        assertThat(overdue.totalAmount()).isEqualByComparingTo("40000.0000");
        assertThat(overdue.overdueAmount()).isEqualByComparingTo("40000.0000");
    }

    @Test
    void reversingAPartialCreditShrinksTheCreditedAmountAndKeepsTheOpenStatus() {
        Policy policy = createPolicy("POL-CN-0025", new BigDecimal("120000.00"));
        PolicyInstallment installment = createPlan(policy, today().plusDays(10))
                .installments().getFirst();
        PolicyCreditNote first = createNote(policy, "10000.00");
        PolicyCreditNote second = createNote(policy, "5000.00");
        issue(policy, first);
        issue(policy, second);
        apply(policy, first, installment.id());
        apply(policy, second, installment.id());

        reverse(policy, second, "over-credited");

        PolicyInstallment restored = billingService.get(TENANT, policy.id()).installments()
                .getFirst();
        assertThat(restored.status()).isEqualTo("DUE");
        assertThat(restored.creditedAmount()).isEqualByComparingTo("10000.0000");
        assertThat(restored.outstandingAmount()).isEqualByComparingTo("30000.0000");

        assertThat(noteById(policy, second.id()).status()).isEqualTo("REVERSED");
        assertThat(noteById(policy, second.id()).appliedInstallmentId())
                .isEqualTo(installment.id());
        assertThat(noteById(policy, first.id()).status()).isEqualTo("APPLIED");

        BillingPlanSummary summary = billingService.get(TENANT, policy.id()).summary();
        assertThat(summary.creditedAmount()).isEqualByComparingTo("10000.0000");
        assertThat(summary.outstandingAmount()).isEqualByComparingTo("110000.0000");
    }

    @Test
    void reversingTheNoteThatSettledACreditedInstallmentReopensIt() {
        Policy policy = createPolicy("POL-CN-0026", new BigDecimal("120000.00"));
        PolicyInstallment installment = createPlan(policy, today().plusDays(10))
                .installments().getFirst();
        PolicyCreditNote first = createNote(policy, "30000.00");
        PolicyCreditNote second = createNote(policy, "10000.00");
        issue(policy, first);
        issue(policy, second);
        apply(policy, first, installment.id());
        apply(policy, second, installment.id());
        assertThat(storedInstallmentStatus(installment.id())).isEqualTo("CREDITED");

        reverse(policy, second, "duplicate billing");

        PolicyInstallment restored = billingService.get(TENANT, policy.id()).installments()
                .getFirst();
        assertThat(restored.status()).isEqualTo("DUE");
        assertThat(restored.creditedAmount()).isEqualByComparingTo("30000.0000");
        assertThat(restored.outstandingAmount()).isEqualByComparingTo("10000.0000");
        assertThat(billingService.get(TENANT, policy.id()).summary().creditedAmount())
                .isEqualByComparingTo("30000.0000");
        assertThat(billingService.get(TENANT, policy.id()).summary().outstandingAmount())
                .isEqualByComparingTo("90000.0000");
    }

    @Test
    void reversingALegacyAppliedNoteWithoutAnInstallmentOnlyMarksTheNote() {
        Policy policy = createPolicy("POL-CN-0027", new BigDecimal("120000.00"));
        PolicyCreditNote note = createNote(policy, "250.00");
        issue(policy, note);
        // A historical APPLIED note predates installment application, so it has
        // no applied_installment_id even though it is APPLIED.
        jdbc.update("""
                UPDATE policy_credit_note
                   SET status = 'APPLIED', applied_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND id = ?
                """, TENANT, note.id());

        PolicyCreditNote reversed = reverse(policy, note, "legacy cleanup");

        assertThat(reversed.status()).isEqualTo("REVERSED");
        assertThat(reversed.appliedInstallmentId()).isNull();
        assertThat(reversed.appliedAt()).isNotNull();
        assertThat(reversed.reversedAt()).isNotNull();
        assertThat(reversed.reversalReason()).isEqualTo("legacy cleanup");

        String payload =
                (String) event("policy.credit-note.reversed", policy.id()).get("payload");
        assertThat(payload)
                .contains("\"creditNoteId\":\"" + note.id() + "\"")
                .contains("\"noteNumber\":\"" + note.noteNumber() + "\"")
                .contains("\"installmentId\":null")
                .contains("\"amount\":\"250.0000\"")
                .contains("\"reason\":\"legacy cleanup\"")
                .contains("\"status\":\"REVERSED\"");
    }

    @Test
    void rejectsReversalOfANoteThatIsNotApplied() {
        Policy policy = createPolicy("POL-CN-0028", new BigDecimal("120000.00"));
        PolicyInstallment installment = createPlan(policy, today().plusDays(10))
                .installments().getFirst();
        PolicyCreditNote note = createNote(policy, "250.00");

        assertConflict(() -> reverse(policy, note, "not applied"),
                "INVALID_CREDIT_NOTE_TRANSITION");
        issue(policy, note);
        assertConflict(() -> reverse(policy, note, "not applied"),
                "INVALID_CREDIT_NOTE_TRANSITION");

        apply(policy, note, installment.id());
        reverse(policy, note, "first reversal");
        assertConflict(() -> reverse(policy, note, "second reversal"),
                "INVALID_CREDIT_NOTE_TRANSITION");

        PolicyCreditNote cancelled = createNote(policy, "100.00");
        creditNoteService.updateStatus(TENANT, policy.id(), cancelled.id(),
                new UpdateCreditNoteStatusRequest("CANCELLED"));
        assertConflict(() -> reverse(policy, cancelled, "cancelled note"),
                "INVALID_CREDIT_NOTE_TRANSITION");

        assertThat(noteById(policy, note.id()).status()).isEqualTo("REVERSED");
        assertThat(countEvents("policy.credit-note.reversed")).isEqualTo(1);
    }

    @Test
    void requiresAReversalReasonWithinTheLimit() {
        Policy policy = createPolicy("POL-CN-0029", new BigDecimal("120000.00"));
        PolicyInstallment installment = createPlan(policy, today().plusDays(10))
                .installments().getFirst();
        PolicyCreditNote note = createNote(policy, "250.00");
        issue(policy, note);
        apply(policy, note, installment.id());

        assertBadRequest(() -> creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("REVERSED", null, null)),
                "INVALID_CREDIT_NOTE_REASON");
        assertBadRequest(() -> reverse(policy, note, "   "), "INVALID_CREDIT_NOTE_REASON");
        assertBadRequest(() -> reverse(policy, note, "x".repeat(501)),
                "INVALID_CREDIT_NOTE_REASON");

        assertThat(noteById(policy, note.id()).status()).isEqualTo("APPLIED");
        assertThat(countEvents("policy.credit-note.reversed")).isZero();
        PolicyInstallment unchanged = billingService.get(TENANT, policy.id()).installments()
                .getFirst();
        assertThat(unchanged.creditedAmount()).isEqualByComparingTo("250.0000");

        PolicyCreditNote reversed = reverse(policy, note, "x".repeat(500));
        assertThat(reversed.reversalReason()).hasSize(500);
    }

    @Test
    void reversalNeverDrivesTheCreditedAmountBelowZero() {
        Policy policy = createPolicy("POL-CN-0030", new BigDecimal("120000.00"));
        PolicyInstallment installment = createPlan(policy, today().plusDays(10))
                .installments().getFirst();
        PolicyCreditNote note = createNote(policy, "40000.00");
        issue(policy, note);
        apply(policy, note, installment.id());
        jdbc.update("UPDATE policy_installment SET credited_amount = 1000.0000 WHERE id = ?",
                installment.id());

        reverse(policy, note, "mismatched ledger");

        PolicyInstallment restored = billingService.get(TENANT, policy.id()).installments()
                .getFirst();
        assertThat(restored.creditedAmount()).isEqualByComparingTo("0.0000");
        assertThat(restored.status()).isEqualTo("DUE");
        assertThat(restored.outstandingAmount()).isEqualByComparingTo("40000.0000");
    }

    @Test
    void enforcesTheTenantAndPolicyWallWhenReversing() {
        Policy policy = createPolicy("POL-CN-0031", new BigDecimal("120000.00"));
        PolicyInstallment installment = createPlan(policy, today().plusDays(10))
                .installments().getFirst();
        PolicyCreditNote note = createNote(policy, "250.00");
        issue(policy, note);
        apply(policy, note, installment.id());

        assertNotFound(() -> creditNoteService.updateStatus(OTHER_TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("REVERSED", null, "foreign tenant")));
        Policy otherPolicy = createPolicy("POL-CN-0032", new BigDecimal("90000.00"));
        assertNotFound(() -> creditNoteService.updateStatus(TENANT, otherPolicy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("REVERSED", null, "foreign policy")));

        assertThat(noteById(policy, note.id()).status()).isEqualTo("APPLIED");
        assertThat(billingService.get(TENANT, policy.id()).installments().getFirst()
                .creditedAmount()).isEqualByComparingTo("250.0000");
        assertThat(countEvents("policy.credit-note.reversed")).isZero();
    }

    @Test
    void writesTheReversedEventWithTheReversalPayload() {
        Policy policy = createPolicy("POL-CN-0033", new BigDecimal("120000.00"));
        PolicyInstallment installment = createPlan(policy, today().plusDays(10))
                .installments().getFirst();
        PolicyCreditNote note = createNote(policy, "40000.00");
        issue(policy, note);
        apply(policy, note, installment.id());

        reverse(policy, note, "customer dispute");

        Map<String, Object> event = event("policy.credit-note.reversed", policy.id());
        assertThat(event.get("aggregate_type")).isEqualTo("Policy");
        assertThat(event.get("aggregate_version"))
                .isEqualTo(policyService.get(TENANT, policy.id()).version() + 1L);
        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"creditNoteId\":\"" + note.id() + "\"")
                .contains("\"noteNumber\":\"" + note.noteNumber() + "\"")
                .contains("\"installmentId\":\"" + installment.id() + "\"")
                .contains("\"amount\":\"40000.0000\"")
                .contains("\"reason\":\"customer dispute\"")
                .contains("\"status\":\"REVERSED\"");
        assertThat((String) event.get("headers"))
                .contains("\"book\":\"DIRECT\"")
                .contains("\"vertical\":\"policy\"");
        OutboxEvent stored = outboxRepository.findById((UUID) event.get("id")).orElseThrow();
        assertThat(stored.toEnvelope().book()).isEqualTo(Book.DIRECT);
        assertThat(stored.toEnvelope().aggregateId()).isEqualTo(policy.id());
    }

    @Test
    void reversalRollbackLeavesTheNoteAppliedAndTheCreditInPlace() {
        Policy policy = createPolicy("POL-CN-0034", new BigDecimal("120000.00"));
        PolicyInstallment installment = createPlan(policy, today().plusDays(10))
                .installments().getFirst();
        PolicyCreditNote note = createNote(policy, "250.00");
        issue(policy, note);
        apply(policy, note, installment.id());
        int versionBefore = policyService.get(TENANT, policy.id()).version();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            reverse(policy, note, "rollback");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        PolicyCreditNote stored = noteById(policy, note.id());
        assertThat(stored.status()).isEqualTo("APPLIED");
        assertThat(stored.reversedAt()).isNull();
        assertThat(stored.reversalReason()).isNull();
        PolicyInstallment unchanged = billingService.get(TENANT, policy.id()).installments()
                .getFirst();
        assertThat(unchanged.status()).isEqualTo("DUE");
        assertThat(unchanged.creditedAmount()).isEqualByComparingTo("250.0000");
        assertThat(countEvents("policy.credit-note.reversed")).isZero();
        assertThat(policyService.get(TENANT, policy.id()).version()).isEqualTo(versionBefore);
    }

    private PolicyCancellation createCancellation(String policyNumber) {
        Policy policy = createPolicy(policyNumber, new BigDecimal("120000.00"));
        jdbc.update("""
                INSERT INTO policy_insured
                       (id, tenant_id, policy_id, party_id, insured_role, display_name,
                        certificate_number, scope, effective_from, effective_to, status, created_at)
                VALUES (?, ?, ?, ?, 'NAMED_INSURED', 'Acme Chemical Corporation',
                        NULL, NULL, ?, ?, 'ACTIVE', ?)
                """, UUID.randomUUID(), TENANT, policy.id(), PARTY, EFFECTIVE, EXPIRATION,
                Timestamp.from(Instant.now()));
        transition(policy.id(), "QUOTED");
        transition(policy.id(), "BOUND");
        transition(policy.id(), "ISSUED");
        return cancellationService.cancel(TENANT, policy.id(),
                new CancelPolicyRequest("PRO_RATA", "Insured sold the business",
                        LocalDate.of(2026, 7, 2), 0, null, null), null, null).cancellation();
    }

    private Policy transition(UUID policyId, String status) {
        return policyService.updateStatus(TENANT, policyId,
                new UpdatePolicyStatusRequest(status, null, null), null);
    }

    private BillingPlanView createPlan(Policy policy) {
        return billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(3, "MONTHLY", FIRST_DUE));
    }

    private BillingPlanView createPlan(Policy policy, LocalDate firstDue) {
        return billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(3, "MONTHLY", firstDue));
    }

    private PolicyCreditNote issue(Policy policy, PolicyCreditNote note) {
        return creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("ISSUED"));
    }

    private PolicyCreditNote apply(Policy policy, PolicyCreditNote note, UUID installmentId) {
        return creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("APPLIED", installmentId));
    }

    private PolicyCreditNote reverse(Policy policy, PolicyCreditNote note, String reason) {
        return creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("REVERSED", null, reason));
    }

    private PolicyCreditNote noteById(Policy policy, UUID creditNoteId) {
        return creditNoteService.list(TENANT, policy.id()).stream()
                .filter(candidate -> candidate.id().equals(creditNoteId))
                .findFirst().orElseThrow();
    }

    private String storedInstallmentStatus(UUID installmentId) {
        return jdbc.queryForObject("SELECT status FROM policy_installment WHERE id = ?",
                String.class, installmentId);
    }

    private static LocalDate today() {
        return LocalDate.now(Clock.systemUTC());
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
                SELECT id, aggregate_type, aggregate_version, status, payload, headers
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                """, policyId, eventType);
    }

    private Map<String, Object> latestEvent(String eventType, UUID policyId) {
        return jdbc.queryForMap("""
                SELECT aggregate_type, aggregate_version, status, payload, headers
                  FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = ?
                 ORDER BY event_sequence DESC
                 LIMIT 1
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
