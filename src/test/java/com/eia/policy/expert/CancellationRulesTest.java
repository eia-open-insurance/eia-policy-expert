package com.eia.policy.expert;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancellationRulesTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);

    @Test
    void normalizesCancellationTypes() {
        assertThat(CancellationRules.normalizeType(" pro_rata ")).isEqualTo("PRO_RATA");
        assertThatThrownBy(() -> CancellationRules.normalizeType("EARLY"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_CANCELLATION_TYPE"));
    }

    @Test
    void onlyIssuedPoliciesCanBeCancelled() {
        assertThatCode(() -> CancellationRules.requireCancellable(policy("IN_FORCE")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> CancellationRules.requireCancellable(policy("DRAFT")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("CANCELLATION_NOT_ALLOWED");
                });
    }

    @Test
    void cancellationDateMustBeInsideThePolicyTerm() {
        assertThatCode(() -> CancellationRules.validateEffectiveDate(LocalDate.of(2026, 6, 1),
                policy("IN_FORCE"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> CancellationRules.validateEffectiveDate(TO, policy("IN_FORCE")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("CANCELLATION_OUTSIDE_POLICY_TERM"));
    }

    @Test
    void nonPaymentRequiresStatutoryNotice() {
        assertThatThrownBy(() -> CancellationRules.validateNotice("NON_PAYMENT", 3))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INSUFFICIENT_CANCELLATION_NOTICE"));
        assertThatCode(() -> CancellationRules.validateNotice("NON_PAYMENT", 10))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> CancellationRules.validateNotice("PRO_RATA", -1))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_CANCELLATION_NOTICE"));
    }

    @Test
    void reinstatementRequiresApproval() {
        assertThatThrownBy(() -> CancellationRules.requireApproval(null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
                    assertThat(api.code()).isEqualTo("APPROVAL_REQUIRED");
                });
    }

    @Test
    void reinstatementRequiresPremiumEvidence() {
        assertThatThrownBy(() -> CancellationRules.requirePremiumEvidence(" ", BigDecimal.TEN))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("PREMIUM_EVIDENCE_REQUIRED"));
        assertThatThrownBy(() -> CancellationRules.requirePremiumEvidence("RCPT-1", BigDecimal.ZERO))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("PREMIUM_EVIDENCE_REQUIRED"));
        assertThatCode(() -> CancellationRules.requirePremiumEvidence("RCPT-1", BigDecimal.TEN))
                .doesNotThrowAnyException();
    }

    @Test
    void reinstatementCannotPrecedeTheCancellation() {
        PolicyCancellation cancellation = new PolicyCancellation(UUID.randomUUID(), TENANT,
                POLICY_ID, UUID.randomUUID(), "PRO_RATA", "Insured request", LocalDate.of(2026, 6, 1),
                0, Instant.now(), 365, 214, new BigDecimal("0.500000"),
                new BigDecimal("60000.0000"), new BigDecimal("60000.0000"), "USD", null, null,
                "ACTIVE", Instant.now());

        assertThatThrownBy(() -> CancellationRules.validateReinstatementDate(
                LocalDate.of(2026, 5, 1), policy("CANCELLED"), cancellation))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("REINSTATEMENT_BEFORE_CANCELLATION"));
    }

    @Test
    void reinstatementRequiresThePolicyCurrency() {
        assertThatThrownBy(() -> CancellationRules.requireSameCurrency("EUR", "USD"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("CURRENCY_MISMATCH"));
    }

    @Test
    void openLinkedCreditNotesBlockReinstatementWithTheirNumbers() {
        for (String status : List.of("DRAFT", "ISSUED", "APPLIED")) {
            assertThatThrownBy(() -> CancellationRules.requireNoOpenCreditNotes(
                    List.of(creditNote("CN-0000001", status), creditNote("CN-0000002", status)),
                    cancellation()))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException api = (ApiException) ex;
                        assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(api.code()).isEqualTo("CANCELLATION_CREDIT_NOTE_OPEN");
                        assertThat(api.getMessage())
                                .contains("CN-0000001 (")
                                .contains("CN-0000002 (")
                                .contains("Cancel or reverse");
                        assertThat(api.meta())
                                .containsEntry("noteNumbers",
                                        List.of("CN-0000001", "CN-0000002"))
                                .containsKey("guidance");
                    });
        }
    }

    @Test
    void closedLinkedCreditNotesDoNotBlockReinstatement() {
        for (String status : List.of("CANCELLED", "REVERSED")) {
            assertThatCode(() -> CancellationRules.requireNoOpenCreditNotes(
                    List.of(creditNote("CN-0000001", status)), cancellation()))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void noLinkedCreditNotesDoNotBlockReinstatement() {
        assertThatCode(() -> CancellationRules.requireNoOpenCreditNotes(List.of(), cancellation()))
                .doesNotThrowAnyException();
    }

    private static PolicyCreditNote creditNote(String noteNumber, String status) {
        return new PolicyCreditNote(UUID.randomUUID(), TENANT, POLICY_ID, noteNumber,
                new BigDecimal("60164.4000"), "USD", "cancellation return premium", status,
                null, null, Instant.now());
    }

    private static PolicyCancellation cancellation() {
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        return new PolicyCancellation(UUID.randomUUID(), TENANT, POLICY_ID, UUID.randomUUID(),
                "PRO_RATA", "Insured sold the business", LocalDate.of(2026, 7, 2), 0, now, 365,
                183, new BigDecimal("0.501370"), new BigDecimal("59835.6000"),
                new BigDecimal("60164.4000"), "USD", UUID.randomUUID(), UUID.randomUUID(),
                "ACTIVE", now, "CX-0000001");
    }

    private static Policy policy(String status) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), status, FROM, TO,
                new BigDecimal("120000.0000"), "USD", new BigDecimal("2000000.0000"), "USD",
                3, now, now);
    }
}
