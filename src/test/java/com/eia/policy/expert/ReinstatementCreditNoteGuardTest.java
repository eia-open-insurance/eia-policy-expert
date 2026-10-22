package com.eia.policy.expert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * End-to-end guard against reinstating a cancelled policy while a credit note
 * linked to the active cancellation is still open. Covers the three blocking
 * statuses, both closed statuses, the no-note path, the tenant wall, and the
 * requirement that a rejection writes no reinstatement, version, event, or
 * idempotency row.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReinstatementCreditNoteGuardTest extends ProductCatalogTestSupport {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID APPROVER = UUID.fromString("55555555-5555-7555-8555-555555555555");
    private static final UUID APPROVAL = UUID.fromString("66666666-6666-7666-8666-666666666666");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);
    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 2, 1);
    private static final LocalDate CANCEL_DATE = LocalDate.of(2026, 7, 2);
    private static final LocalDate REINSTATE_DATE = LocalDate.of(2026, 8, 1);

    @Autowired
    private PolicyService policyService;

    @Autowired
    private CancellationService cancellationService;

    @Autowired
    private CreditNoteService creditNoteService;

    @Autowired
    private BillingService billingService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
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

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ISSUED", "APPLIED"})
    void blocksReinstatementWhileTheLinkedCreditNoteIsOpen(String status) {
        CancellationResult cancelled = cancelPolicy("POL-GUARD-" + status);
        PolicyCreditNote note = linkedNote(cancelled.cancellation());
        advanceTo(status, cancelled.policy(), note);

        assertRejectedWithNoWrites(cancelled.policy(), cancelled.cancellation(), note,
                "guard-" + status);
    }

    @Test
    void allowsReinstatementAfterTheLinkedCreditNoteIsCancelled() {
        CancellationResult cancelled = cancelPolicy("POL-GUARD-CANCELLED");
        PolicyCreditNote note = linkedNote(cancelled.cancellation());
        creditNoteService.updateStatus(TENANT, cancelled.policy().id(), note.id(),
                new UpdateCreditNoteStatusRequest("CANCELLED"));

        ReinstatementResult result = reinstate(cancelled.policy());

        assertThat(result.reinstatement().status()).isEqualTo("APPROVED");
        assertThat(result.policy().status()).isEqualTo("REINSTATED");
        assertThat(noteStatus(note.id())).isEqualTo("CANCELLED");
        assertThat(count("policy_reinstatement")).isEqualTo(1);
        assertThat(countEvents("policy.policy.reinstated")).isEqualTo(1);
    }

    @Test
    void allowsReinstatementAfterTheLinkedCreditNoteIsReversed() {
        CancellationResult cancelled = cancelPolicy("POL-GUARD-REVERSED");
        PolicyCreditNote note = linkedNote(cancelled.cancellation());
        advanceTo("APPLIED", cancelled.policy(), note);
        creditNoteService.updateStatus(TENANT, cancelled.policy().id(), note.id(),
                new UpdateCreditNoteStatusRequest("REVERSED", null, "refund recalled"));

        ReinstatementResult result = reinstate(cancelled.policy());

        assertThat(result.policy().status()).isEqualTo("REINSTATED");
        assertThat(noteStatus(note.id())).isEqualTo("REVERSED");
        assertThat(countEvents("policy.policy.reinstated")).isEqualTo(1);
    }

    @Test
    void allowsReinstatementWhenTheCancellationHasNoLinkedCreditNote() {
        CancellationResult cancelled = cancelPolicy("POL-GUARD-NONE");

        ReinstatementResult result = reinstate(cancelled.policy());

        assertThat(result.policy().status()).isEqualTo("REINSTATED");
        assertThat(count("policy_reinstatement")).isEqualTo(1);
        assertThat(countEvents("policy.policy.reinstated")).isEqualTo(1);
    }

    @Test
    void ignoresALinkedCreditNoteOwnedByAnotherTenant() {
        CancellationResult cancelled = cancelPolicy("POL-GUARD-TENANT");
        UUID foreignNoteId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO policy_credit_note
                       (id, tenant_id, policy_id, note_number, amount, currency, reason, status,
                        created_at, cancellation_id)
                VALUES (?, ?, ?, 'CN-9000001', ?, 'USD', 'foreign tenant adjustment', 'DRAFT', ?, ?)
                """, foreignNoteId, OTHER_TENANT, cancelled.policy().id(),
                cancelled.cancellation().returnPremiumAmount(), Timestamp.from(Instant.now()),
                cancelled.cancellation().id());

        ReinstatementResult result = reinstate(cancelled.policy());

        assertThat(result.policy().status()).isEqualTo("REINSTATED");
        assertThat(noteStatus(foreignNoteId)).isEqualTo("DRAFT");
        assertThat(countEvents("policy.policy.reinstated")).isEqualTo(1);
    }

    private void assertRejectedWithNoWrites(Policy policy, PolicyCancellation cancellation,
                                            PolicyCreditNote note, String idempotencyKey) {
        Policy before = policyService.get(TENANT, policy.id());
        int versionsBefore = countVersions(policy.id());
        String noteStatusBefore = noteStatus(note.id());

        Throwable thrown = catchThrowable(() -> cancellationService.reinstate(TENANT, policy.id(),
                reinstateRequest(), null, idempotencyKey));

        assertThat(thrown).isInstanceOf(ApiException.class);
        ApiException api = (ApiException) thrown;
        assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(api.code()).isEqualTo("CANCELLATION_CREDIT_NOTE_OPEN");
        assertThat(api.getMessage())
                .contains(note.noteNumber())
                .contains("Cancel or reverse");
        assertThat(api.meta()).containsEntry("noteNumbers", List.of(note.noteNumber()));
        assertThat(api.meta()).containsEntry("cancellationId", cancellation.id().toString());

        Policy after = policyService.get(TENANT, policy.id());
        assertThat(after.status()).isEqualTo("CANCELLED");
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(countVersions(policy.id())).isEqualTo(versionsBefore);
        assertThat(count("policy_reinstatement")).isZero();
        assertThat(countEvents("policy.policy.reinstated")).isZero();
        assertThat(countReinstatementVersionEvents(policy.id())).isZero();
        assertThat(count("policy_command_idempotency")).isZero();
        assertThat(noteStatus(note.id())).isEqualTo(noteStatusBefore);
        assertThat(cancellationStatus(cancellation.id())).isEqualTo("ACTIVE");
    }

    private CancellationResult cancelPolicy(String policyNumber) {
        Policy policy = createIssuedPolicy(policyNumber);
        return cancellationService.cancel(TENANT, policy.id(),
                new CancelPolicyRequest("PRO_RATA", "Insured sold the business", CANCEL_DATE, 0,
                        null, null), null, null);
    }

    private Policy createIssuedPolicy(String policyNumber) {
        Policy policy = policyService.create(new CreatePolicyRequest(TENANT, policyNumber, "CGL",
                PARTY, null, EFFECTIVE, EXPIRATION, new BigDecimal("120000.00"), "USD",
                new BigDecimal("5000000.00"), "USD"));
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
        return transition(policy.id(), "ISSUED");
    }

    private Policy transition(UUID policyId, String target) {
        return policyService.updateStatus(TENANT, policyId,
                new UpdatePolicyStatusRequest(target, null, null), null);
    }

    private PolicyCreditNote linkedNote(PolicyCancellation cancellation) {
        return creditNoteService.create(TENANT, cancellation.policyId(),
                new CreateCreditNoteRequest(cancellation.returnPremiumAmount(),
                        cancellation.premiumCurrency(), "cancellation return premium",
                        cancellation.id()));
    }

    private void advanceTo(String status, Policy policy, PolicyCreditNote note) {
        switch (status) {
            case "DRAFT" -> {
                // Nothing to do: creation already leaves the note DRAFT.
            }
            case "ISSUED" -> issue(policy, note);
            case "APPLIED" -> {
                PolicyInstallment installment = createPlan(policy).installments().getFirst();
                issue(policy, note);
                creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                        new UpdateCreditNoteStatusRequest("APPLIED", installment.id()));
            }
            default -> throw new IllegalArgumentException("Unhandled credit note status " + status);
        }
    }

    private PolicyCreditNote issue(Policy policy, PolicyCreditNote note) {
        return creditNoteService.updateStatus(TENANT, policy.id(), note.id(),
                new UpdateCreditNoteStatusRequest("ISSUED"));
    }

    private BillingPlanView createPlan(Policy policy) {
        return billingService.create(TENANT, policy.id(),
                new CreateBillingPlanRequest(1, "ANNUAL", FIRST_DUE));
    }

    private ReinstatementResult reinstate(Policy policy) {
        return cancellationService.reinstate(TENANT, policy.id(), reinstateRequest(), null, null);
    }

    private static ReinstatePolicyRequest reinstateRequest() {
        return new ReinstatePolicyRequest(REINSTATE_DATE, "Premium received in full", APPROVER,
                APPROVAL, "RCPT-2026-0001", new BigDecimal("1500.00"), "USD");
    }

    private String noteStatus(UUID creditNoteId) {
        return jdbc.queryForObject("SELECT status FROM policy_credit_note WHERE id = ?",
                String.class, creditNoteId);
    }

    private String cancellationStatus(UUID cancellationId) {
        return jdbc.queryForObject("SELECT status FROM policy_cancellation WHERE id = ?",
                String.class, cancellationId);
    }

    private int countVersions(UUID policyId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM policy_version
                 WHERE tenant_id = ? AND policy_id = ?
                """, Integer.class, TENANT, policyId);
    }

    private int countEvents(String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ?", Integer.class,
                eventType);
    }

    private int countReinstatementVersionEvents(UUID policyId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE aggregate_id = ? AND event_type = 'policy.policy.version-issued'
                   AND payload LIKE '%"changeType":"REINSTATEMENT"%'
                """, Integer.class, policyId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
