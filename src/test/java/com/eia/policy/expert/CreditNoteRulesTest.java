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
 * Pure credit note rules: the DRAFT/ISSUED/APPLIED/CANCELLED graph, status and
 * currency vocabulary, positive money, and the 500-character reason cap.
 */
class CreditNoteRulesTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");

    @Test
    void allowsOnlyTheDeclaredTransitions() {
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.DRAFT, CreditNoteRules.ISSUED)).isTrue();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.DRAFT, CreditNoteRules.CANCELLED)).isTrue();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.DRAFT, CreditNoteRules.APPLIED)).isFalse();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.ISSUED, CreditNoteRules.APPLIED)).isTrue();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.ISSUED, CreditNoteRules.CANCELLED)).isTrue();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.ISSUED, CreditNoteRules.ISSUED)).isFalse();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.APPLIED, CreditNoteRules.CANCELLED)).isFalse();
        assertThat(CreditNoteRules.canTransition(
                CreditNoteRules.CANCELLED, CreditNoteRules.ISSUED)).isFalse();
        assertThat(CreditNoteRules.canTransition(null, CreditNoteRules.ISSUED)).isFalse();
    }

    @Test
    void treatsAppliedAndCancelledAsTerminal() {
        assertThat(CreditNoteRules.isTerminal(CreditNoteRules.APPLIED)).isTrue();
        assertThat(CreditNoteRules.isTerminal(CreditNoteRules.CANCELLED)).isTrue();
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

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
