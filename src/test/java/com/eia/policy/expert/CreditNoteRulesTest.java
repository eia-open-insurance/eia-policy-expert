package com.eia.policy.expert;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure credit note rules: the DRAFT/ISSUED/APPLIED/CANCELLED/REVERSED graph,
 * status and currency vocabulary, positive money, the 500-character reason cap,
 * and the reversal restore arithmetic.
 */
class CreditNoteRulesTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    @Test
    void allowsOnlyTheDeclaredTransitions() {
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.DRAFT, CreditNoteRules.ISSUED)).isTrue();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.DRAFT, CreditNoteRules.CANCELLED)).isTrue();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.DRAFT, CreditNoteRules.APPLIED)).isFalse();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.DRAFT, CreditNoteRules.REVERSED)).isFalse();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.ISSUED, CreditNoteRules.APPLIED)).isTrue();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.ISSUED, CreditNoteRules.CANCELLED)).isTrue();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.ISSUED, CreditNoteRules.REVERSED)).isFalse();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.ISSUED, CreditNoteRules.ISSUED)).isFalse();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.APPLIED, CreditNoteRules.CANCELLED)).isFalse();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.APPLIED, CreditNoteRules.REVERSED)).isTrue();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.APPLIED, CreditNoteRules.ISSUED)).isFalse();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.CANCELLED, CreditNoteRules.ISSUED)).isFalse();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.CANCELLED, CreditNoteRules.REVERSED)).isFalse();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.REVERSED, CreditNoteRules.ISSUED)).isFalse();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.REVERSED, CreditNoteRules.APPLIED)).isFalse();
        assertThat(CreditNoteRules.canTransition(null, CreditNoteRules.ISSUED)).isFalse();
    }

    @Test
    void treatsAppliedCancelledAndReversedAsTerminal() {
        assertThat(CreditNoteRules.isTerminal(CreditNoteRules.APPLIED)).isTrue();
        assertThat(CreditNoteRules.isTerminal(CreditNoteRules.CANCELLED)).isTrue();
        assertThat(CreditNoteRules.isTerminal(CreditNoteRules.REVERSED)).isTrue();
        assertThat(CreditNoteRules.isTerminal(CreditNoteRules.DRAFT)).isFalse();
        assertThat(CreditNoteRules.isTerminal(CreditNoteRules.ISSUED)).isFalse();
    }

    @Test
    void normalizesTheTargetStatusAndRejectsDraftOrUnknownValues() {
        assertThat(CreditNoteRules.normalizeTargetStatus(" issued "))
                .isEqualTo(CreditNoteRules.ISSUED);
        assertThat(CreditNoteRules.normalizeTargetStatus("Applied"))
                .isEqualTo(CreditNoteRules.APPLIED);
        assertThat(CreditNoteRules.normalizeTargetStatus("cancelled"))
                .isEqualTo(CreditNoteRules.CANCELLED);
        assertThat(CreditNoteRules.normalizeTargetStatus(" Reversed "))
                .isEqualTo(CreditNoteRules.REVERSED);

        assertBadRequest(() -> CreditNoteRules.normalizeTargetStatus("DRAFT"),
                "INVALID_CREDIT_NOTE_STATUS");
        assertBadRequest(() -> CreditNoteRules.normalizeTargetStatus("VOID"),
                "INVALID_CREDIT_NOTE_STATUS");
        assertBadRequest(() -> CreditNoteRules.normalizeTargetStatus(null),
                "INVALID_CREDIT_NOTE_STATUS");
    }

    @Test
    void validatesAndNormalizesACreateRequest() {
        CreditNoteRules.CreditNoteSpec spec = CreditNoteRules.validateCreate(
                new CreateCreditNoteRequest(new BigDecimal("150.5"), " usd ",
                        "  promotional adjustment  "), policy());

        assertThat(spec.amount()).isEqualByComparingTo("150.5000");
        assertThat(spec.amount().scale()).isEqualTo(Money.SCALE);
        assertThat(spec.currency()).isEqualTo("USD");
        assertThat(spec.reason()).isEqualTo("promotional adjustment");
    }

    @Test
    void blankReasonIsStoredAsNull() {
        assertThat(CreditNoteRules.normalizeReason(null)).isNull();
        assertThat(CreditNoteRules.normalizeReason("   ")).isNull();
    }

    @Test
    void acceptsAReasonOfExactlyFiveHundredCharacters() {
        String reason = "x".repeat(CreditNoteRules.MAX_REASON_LENGTH);

        assertThat(CreditNoteRules.normalizeReason(reason)).hasSize(500);
    }

    @Test
    void rejectsAReasonLongerThanFiveHundredCharacters() {
        String reason = "x".repeat(CreditNoteRules.MAX_REASON_LENGTH + 1);

        assertBadRequest(() -> CreditNoteRules.normalizeReason(reason),
                "INVALID_CREDIT_NOTE_REASON");
    }

    @Test
    void requiresATrimmedReversalReasonWithinTheLimit() {
        assertThat(CreditNoteRules.normalizeReversalReason("  billing correction  "))
                .isEqualTo("billing correction");
        assertThat(CreditNoteRules.normalizeReversalReason(
                "x".repeat(CreditNoteRules.MAX_REASON_LENGTH))).hasSize(500);

        assertBadRequest(() -> CreditNoteRules.normalizeReversalReason(null),
                "INVALID_CREDIT_NOTE_REASON");
        assertBadRequest(() -> CreditNoteRules.normalizeReversalReason("   "),
                "INVALID_CREDIT_NOTE_REASON");
        assertBadRequest(() -> CreditNoteRules.normalizeReversalReason(
                "x".repeat(CreditNoteRules.MAX_REASON_LENGTH + 1)),
                "INVALID_CREDIT_NOTE_REASON");
    }

    @Test
    void reversalClampsTheCreditedTotalAtZero() {
        assertThat(CreditNoteRules.creditedAfterReversal(
                installment("1000.0000", "500.0000", BillingRules.DUE),
                new BigDecimal("200.00"))).isEqualByComparingTo("300.0000");
        assertThat(CreditNoteRules.creditedAfterReversal(
                installment("1000.0000", "100.0000", BillingRules.DUE),
                new BigDecimal("200.00"))).isEqualByComparingTo("0.0000");
        assertThat(CreditNoteRules.creditedAfterReversal(
                installment("1000.0000", "0.0000", BillingRules.DUE),
                new BigDecimal("200.00"))).isEqualByComparingTo("0.0000");
        assertThat(CreditNoteRules.creditedAfterReversal(
                installment("40000.0000", "40000.0000", BillingRules.CREDITED),
                new BigDecimal("40000.00"))).isEqualByComparingTo("0.0000");
    }

    @Test
    void reversalRestoresACreditedInstallmentToDueOrOverdue() {
        PolicyInstallment future = installmentOn(TODAY.plusDays(10), "40000.0000",
                "40000.0000", BillingRules.CREDITED);
        assertThat(CreditNoteRules.statusAfterReversal(future, new BigDecimal("40000.00"), TODAY))
                .isEqualTo(BillingRules.DUE);

        PolicyInstallment dueToday = installmentOn(TODAY, "40000.0000", "40000.0000",
                BillingRules.CREDITED);
        assertThat(CreditNoteRules.statusAfterReversal(dueToday, new BigDecimal("40000.00"),
                TODAY)).isEqualTo(BillingRules.DUE);

        PolicyInstallment pastDue = installmentOn(TODAY.minusDays(1), "40000.0000",
                "40000.0000", BillingRules.CREDITED);
        assertThat(CreditNoteRules.statusAfterReversal(pastDue, new BigDecimal("40000.00"),
                TODAY)).isEqualTo(BillingRules.OVERDUE);

        PolicyInstallment partialCredited = installmentOn(TODAY.plusDays(10), "40000.0000",
                "10000.0000", BillingRules.DUE);
        assertThat(CreditNoteRules.statusAfterReversal(partialCredited,
                new BigDecimal("10000.00"), TODAY)).isEqualTo(BillingRules.DUE);

        PolicyInstallment overdue = installmentOn(TODAY.minusDays(3), "40000.0000",
                "10000.0000", BillingRules.OVERDUE);
        assertThat(CreditNoteRules.statusAfterReversal(overdue, new BigDecimal("10000.00"),
                TODAY)).isEqualTo(BillingRules.OVERDUE);

        PolicyInstallment paid = installmentOn(TODAY.plusDays(10), "40000.0000", "0.0000",
                BillingRules.PAID);
        assertThat(CreditNoteRules.statusAfterReversal(paid, new BigDecimal("10000.00"), TODAY))
                .isEqualTo(BillingRules.PAID);
    }

    @Test
    void rejectsMissingZeroNegativeAndRoundingToZeroAmounts() {
        assertBadRequest(() -> CreditNoteRules.validateCreate(
                new CreateCreditNoteRequest(null, "USD", null), policy()),
                "INVALID_CREDIT_NOTE_AMOUNT");
        assertBadRequest(() -> CreditNoteRules.validateCreate(
                new CreateCreditNoteRequest(BigDecimal.ZERO, "USD", null), policy()),
                "INVALID_CREDIT_NOTE_AMOUNT");
        assertBadRequest(() -> CreditNoteRules.validateCreate(
                new CreateCreditNoteRequest(new BigDecimal("-1.00"), "USD", null), policy()),
                "INVALID_CREDIT_NOTE_AMOUNT");
        assertBadRequest(() -> CreditNoteRules.validateCreate(
                new CreateCreditNoteRequest(new BigDecimal("0.00001"), "USD", null), policy()),
                "INVALID_CREDIT_NOTE_AMOUNT");
    }

    @Test
    void requiresThePolicyCurrency() {
        assertBadRequest(() -> CreditNoteRules.validateCreate(
                new CreateCreditNoteRequest(BigDecimal.ONE, "EUR", null), policy()),
                "CURRENCY_MISMATCH");
        assertBadRequest(() -> CreditNoteRules.validateCreate(
                new CreateCreditNoteRequest(BigDecimal.ONE, "US", null), policy()),
                "CURRENCY_MISMATCH");
        assertBadRequest(() -> CreditNoteRules.validateCreate(
                new CreateCreditNoteRequest(BigDecimal.ONE, null, null), policy()),
                "CURRENCY_MISMATCH");
    }

    @Test
    void rejectsANullRequest() {
        assertBadRequest(() -> CreditNoteRules.validateCreate(null, policy()),
                "INVALID_CREDIT_NOTE");
    }

    @Test
    void validatesAReturnPremiumNoteAgainstItsCancellation() {
        CreditNoteRules.CreditNoteSpec exact = spec("60164.4000", "USD");
        CreditNoteRules.validateCancellationLink(exact, cancellation("ACTIVE", "60164.4000",
                "USD"));
        CreditNoteRules.validateCancellationLink(spec("60164.4100", "USD"),
                cancellation("ACTIVE", "60164.4000", "USD"));
        CreditNoteRules.validateCancellationLink(spec("60164.3900", "USD"),
                cancellation("ACTIVE", "60164.4000", "USD"));

        assertBadRequest(() -> CreditNoteRules.validateCancellationLink(
                spec("60164.4200", "USD"), cancellation("ACTIVE", "60164.4000", "USD")),
                "CREDIT_NOTE_AMOUNT_MISMATCH");
        assertBadRequest(() -> CreditNoteRules.validateCancellationLink(
                spec("60164.4000", "EUR"), cancellation("ACTIVE", "60164.4000", "USD")),
                "CURRENCY_MISMATCH");
        assertConflict(() -> CreditNoteRules.validateCancellationLink(
                spec("60164.4000", "USD"), cancellation("REINSTATED", "60164.4000", "USD")),
                "CANCELLATION_NOT_ACTIVE");
        assertConflict(() -> CreditNoteRules.validateCancellationLink(
                spec("60164.4000", "USD"), cancellation("VOID", "60164.4000", "USD")),
                "CANCELLATION_NOT_ACTIVE");
    }

    @Test
    void applyingRequiresAnInstallmentAndAnOpenRow() {
        assertBadRequest(() -> CreditNoteRules.requireInstallmentId(null),
                "INVALID_CREDIT_NOTE_INSTALLMENT");
        assertThat(CreditNoteRules.requireInstallmentId(POLICY_ID)).isEqualTo(POLICY_ID);

        assertConflict(() -> CreditNoteRules.validateApply(note("100.00"),
                        installment("1000.0000", "0.0000", BillingRules.PAID)),
                "INSTALLMENT_NOT_CREDITABLE");
        assertConflict(() -> CreditNoteRules.validateApply(note("100.00"),
                        installment("1000.0000", "0.0000", BillingRules.WAIVED)),
                "INSTALLMENT_NOT_CREDITABLE");
        assertConflict(() -> CreditNoteRules.validateApply(note("100.00"),
                        installment("1000.0000", "1000.0000", BillingRules.CREDITED)),
                "INSTALLMENT_NOT_CREDITABLE");
    }

    @Test
    void applyingRejectsAnAmountAboveTheUncreditedRemainder() {
        assertConflict(() -> CreditNoteRules.validateApply(note("600.01"),
                        installment("1000.0000", "400.0000", BillingRules.DUE)),
                "CREDIT_NOTE_EXCEEDS_INSTALLMENT");
        assertThat(CreditNoteRules.creditedAfter(
                installment("1000.0000", "400.0000", BillingRules.DUE),
                new BigDecimal("600.00"))).isEqualByComparingTo("1000.0000");
        assertThat(CreditNoteRules.statusAfterCredit(
                installment("1000.0000", "400.0000", BillingRules.OVERDUE),
                new BigDecimal("600.00"))).isEqualTo(BillingRules.CREDITED);
        assertThat(CreditNoteRules.statusAfterCredit(
                installment("1000.0000", "400.0000", BillingRules.DUE),
                new BigDecimal("100.00"))).isEqualTo(BillingRules.DUE);
    }

    private static PolicyCreditNote note(String amount) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new PolicyCreditNote(UUID.randomUUID(), TENANT, POLICY_ID, "CN-0000001",
                new BigDecimal(amount), "USD", null, CreditNoteRules.ISSUED, now, null, null, now);
    }

    private static PolicyInstallment installment(String amount, String creditedAmount,
                                                 String status) {
        return installmentOn(LocalDate.of(2026, 2, 1), amount, creditedAmount, status);
    }

    private static PolicyInstallment installmentOn(LocalDate dueDate, String amount,
                                                   String creditedAmount, String status) {
        return new PolicyInstallment(UUID.randomUUID(), TENANT, POLICY_ID, 1, dueDate,
                new BigDecimal(amount), new BigDecimal(creditedAmount), status, null, null,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static CreditNoteRules.CreditNoteSpec spec(String amount, String currency) {
        return new CreditNoteRules.CreditNoteSpec(new BigDecimal(amount), currency, null);
    }

    private static PolicyCancellation cancellation(String status, String returnPremium,
                                                  String currency) {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        return new PolicyCancellation(UUID.randomUUID(), TENANT, POLICY_ID, UUID.randomUUID(),
                "PRO_RATA", "Insured sold the business", LocalDate.of(2026, 7, 2), 0, now,
                365, 183, new BigDecimal("0.501370"), new BigDecimal("59835.6000"),
                new BigDecimal(returnPremium), currency, null, null, status, now);
    }

    private static Policy policy() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL", UUID.randomUUID(),
                "ISSUED", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                new BigDecimal("120000.0000"), "USD", new BigDecimal("5000000.0000"), "USD",
                0, now, now);
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

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
