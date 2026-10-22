package com.eia.policy.expert;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnderwritingSubmissionRulesTest {
    private static final UUID APPLICANT =
            UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 11, 1);

    @Test
    void normalizesTheCreateBody() {
        UnderwritingSubmissionRules.UnderwritingSubmissionSpec spec =
                UnderwritingSubmissionRules.validateCreate(new CreateUnderwritingSubmissionRequest(
                        APPLICANT, "  cgl-products  ", EFFECTIVE,
                        new BigDecimal("2500000"), " usd ",
                        List.of("  ASBESTOS ", "PFAS"), "  A large manufacturing risk  "));

        assertThat(spec.applicantPartyId()).isEqualTo(APPLICANT);
        assertThat(spec.productCode()).isEqualTo("cgl-products");
        assertThat(spec.requestedEffectiveDate()).isEqualTo(EFFECTIVE);
        assertThat(spec.requestedLimitAmount()).isEqualByComparingTo("2500000.0000");
        assertThat(spec.requestedLimitAmount().scale()).isEqualTo(4);
        assertThat(spec.requestedLimitCurrency()).isEqualTo("USD");
        assertThat(spec.tortCodes()).containsExactly("ASBESTOS", "PFAS");
        assertThat(spec.notes()).isEqualTo("A large manufacturing risk");
    }

    @Test
    void optionalTortCodesAndNotesBecomeEmptyOrNull() {
        UnderwritingSubmissionRules.UnderwritingSubmissionSpec spec =
                UnderwritingSubmissionRules.validateCreate(new CreateUnderwritingSubmissionRequest(
                        APPLICANT, "CGL", EFFECTIVE, BigDecimal.ZERO, "USD", null, "   "));

        assertThat(spec.tortCodes()).isEmpty();
        assertThat(spec.notes()).isNull();
        assertThat(spec.requestedLimitAmount()).isEqualByComparingTo("0.0000");
    }

    @Test
    void acceptsTwentyTortCodesOfThirtyTwoCharacters() {
        List<String> codes = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            codes.add("T".repeat(32));
        }

        UnderwritingSubmissionRules.UnderwritingSubmissionSpec spec =
                UnderwritingSubmissionRules.validateCreate(new CreateUnderwritingSubmissionRequest(
                        APPLICANT, "CGL", EFFECTIVE, BigDecimal.ONE, "USD", codes, null));

        assertThat(spec.tortCodes()).hasSize(20);
        assertThat(spec.tortCodes()).allSatisfy(code -> assertThat(code).hasSize(32));
    }

    @Test
    void rejectsANullRequest() {
        assertThatThrownBy(() -> UnderwritingSubmissionRules.validateCreate(null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_SUBMISSION"));
    }

    @Test
    void rejectsAMissingApplicant() {
        assertThatThrownBy(() -> UnderwritingSubmissionRules.validateCreate(
                new CreateUnderwritingSubmissionRequest(null, "CGL", EFFECTIVE,
                        BigDecimal.TEN, "USD", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.code()).isEqualTo("INVALID_SUBMISSION_APPLICANT");
                    assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                            .containsExactly("applicantPartyId");
                });
    }

    @Test
    void rejectsABlankOrOverlongProductCode() {
        assertThatThrownBy(() -> UnderwritingSubmissionRules.validateCreate(
                new CreateUnderwritingSubmissionRequest(APPLICANT, "   ", EFFECTIVE,
                        BigDecimal.TEN, "USD", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_SUBMISSION_PRODUCT_CODE"));

        assertThatThrownBy(() -> UnderwritingSubmissionRules.validateCreate(
                new CreateUnderwritingSubmissionRequest(APPLICANT, "P".repeat(65), EFFECTIVE,
                        BigDecimal.TEN, "USD", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_SUBMISSION_PRODUCT_CODE"));
    }

    @Test
    void rejectsAMissingEffectiveDate() {
        assertThatThrownBy(() -> UnderwritingSubmissionRules.validateCreate(
                new CreateUnderwritingSubmissionRequest(APPLICANT, "CGL", null,
                        BigDecimal.TEN, "USD", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.code()).isEqualTo("INVALID_SUBMISSION_DATE");
                    assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                            .containsExactly("requestedEffectiveDate");
                });
    }

    @Test
    void rejectsAMissingOrNegativeLimit() {
        assertThatThrownBy(() -> UnderwritingSubmissionRules.validateCreate(
                new CreateUnderwritingSubmissionRequest(APPLICANT, "CGL", EFFECTIVE,
                        null, "USD", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_SUBMISSION_LIMIT"));

        assertThatThrownBy(() -> UnderwritingSubmissionRules.validateCreate(
                new CreateUnderwritingSubmissionRequest(APPLICANT, "CGL", EFFECTIVE,
                        new BigDecimal("-0.0001"), "USD", null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_SUBMISSION_LIMIT"));
    }

    @Test
    void rejectsBadCurrencyCodes() {
        for (String currency : new String[]{"US", "USDX", "12A", "", null}) {
            assertThatThrownBy(() -> UnderwritingSubmissionRules.validateCreate(
                    new CreateUnderwritingSubmissionRequest(APPLICANT, "CGL", EFFECTIVE,
                            BigDecimal.TEN, currency, null, null)))
                    .as("currency %s", currency)
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).code())
                            .isEqualTo("INVALID_SUBMISSION_CURRENCY"));
        }
    }

    @Test
    void rejectsMoreThanTwentyTortCodes() {
        List<String> codes = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            codes.add("CODE-" + index);
        }

        assertThatThrownBy(() -> UnderwritingSubmissionRules.validateCreate(
                new CreateUnderwritingSubmissionRequest(APPLICANT, "CGL", EFFECTIVE,
                        BigDecimal.TEN, "USD", codes, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_SUBMISSION_TORT_CODES"));
    }

    @Test
    void rejectsABlankOrOverlongTortCode() {
        assertThatThrownBy(() -> UnderwritingSubmissionRules.validateCreate(
                new CreateUnderwritingSubmissionRequest(APPLICANT, "CGL", EFFECTIVE,
                        BigDecimal.TEN, "USD", List.of("ASBESTOS", "  "), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_SUBMISSION_TORT_CODES"));

        assertThatThrownBy(() -> UnderwritingSubmissionRules.validateCreate(
                new CreateUnderwritingSubmissionRequest(APPLICANT, "CGL", EFFECTIVE,
                        BigDecimal.TEN, "USD", List.of("T".repeat(33)), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_SUBMISSION_TORT_CODES"));
    }

    @Test
    void rejectsOverlongNotes() {
        assertThatThrownBy(() -> UnderwritingSubmissionRules.validateCreate(
                new CreateUnderwritingSubmissionRequest(APPLICANT, "CGL", EFFECTIVE,
                        BigDecimal.TEN, "USD", null, "N".repeat(2001))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.code()).isEqualTo("INVALID_SUBMISSION_NOTES");
                    assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                            .containsExactly("notes");
                });
    }

    @Test
    void normalizesStatusCaselesslyAndRejectsUnknownValues() {
        assertThat(UnderwritingSubmissionRules.normalizeStatus(" received ")).isEqualTo("RECEIVED");
        assertThat(UnderwritingSubmissionRules.normalizeStatus("quoted")).isEqualTo("QUOTED");

        assertThatThrownBy(() -> UnderwritingSubmissionRules.normalizeStatus("APPROVED"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_SUBMISSION_STATUS"));
        assertThatThrownBy(() -> UnderwritingSubmissionRules.normalizeStatus(null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void acceptsTheSpecifiedTransitionGraph() {
        assertThat(UnderwritingSubmissionRules.canTransition("RECEIVED", "REFERRED")).isTrue();
        assertThat(UnderwritingSubmissionRules.canTransition("RECEIVED", "QUOTED")).isTrue();
        assertThat(UnderwritingSubmissionRules.canTransition("RECEIVED", "DECLINED")).isTrue();
        assertThat(UnderwritingSubmissionRules.canTransition("RECEIVED", "WITHDRAWN")).isTrue();
        assertThat(UnderwritingSubmissionRules.canTransition("REFERRED", "QUOTED")).isTrue();
        assertThat(UnderwritingSubmissionRules.canTransition("REFERRED", "DECLINED")).isTrue();
        assertThat(UnderwritingSubmissionRules.canTransition("REFERRED", "WITHDRAWN")).isTrue();
        assertThat(UnderwritingSubmissionRules.canTransition("QUOTED", "BOUND")).isTrue();
        assertThat(UnderwritingSubmissionRules.canTransition("QUOTED", "DECLINED")).isTrue();
        assertThat(UnderwritingSubmissionRules.canTransition("QUOTED", "WITHDRAWN")).isTrue();
    }

    @Test
    void rejectsEveryOtherTransition() {
        List<String> statuses = List.of("RECEIVED", "REFERRED", "QUOTED", "BOUND",
                "DECLINED", "WITHDRAWN");
        for (String from : statuses) {
            for (String to : statuses) {
                boolean allowed = switch (from) {
                    case "RECEIVED" -> List.of("REFERRED", "QUOTED", "DECLINED", "WITHDRAWN")
                            .contains(to);
                    case "REFERRED" -> List.of("QUOTED", "DECLINED", "WITHDRAWN").contains(to);
                    case "QUOTED" -> List.of("BOUND", "DECLINED", "WITHDRAWN").contains(to);
                    default -> false;
                };
                assertThat(UnderwritingSubmissionRules.canTransition(from, to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(allowed);
            }
        }
    }

    @Test
    void identifiesTheTerminalStatuses() {
        assertThat(UnderwritingSubmissionRules.isTerminal("BOUND")).isTrue();
        assertThat(UnderwritingSubmissionRules.isTerminal("DECLINED")).isTrue();
        assertThat(UnderwritingSubmissionRules.isTerminal("WITHDRAWN")).isTrue();
        assertThat(UnderwritingSubmissionRules.isTerminal("RECEIVED")).isFalse();
        assertThat(UnderwritingSubmissionRules.isTerminal("REFERRED")).isFalse();
        assertThat(UnderwritingSubmissionRules.isTerminal("QUOTED")).isFalse();
    }

    @Test
    void normalizesTheOptionalReason() {
        assertThat(UnderwritingSubmissionRules.normalizeReason("  Large limit  "))
                .isEqualTo("Large limit");
        assertThat(UnderwritingSubmissionRules.normalizeReason("   ")).isNull();
        assertThat(UnderwritingSubmissionRules.normalizeReason(null)).isNull();

        assertThatThrownBy(() -> UnderwritingSubmissionRules.normalizeReason("R".repeat(501)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_SUBMISSION_REASON"));
    }
}
