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

    @Test
    void normalizesAConversionBodyAndDefaultsTheExpiration() {
        UnderwritingSubmissionRules.ConversionSpec spec =
                UnderwritingSubmissionRules.validateConversion(submission("QUOTED"),
                        new ConvertSubmissionRequest(new BigDecimal("125000"), " usd ", null));

        assertThat(spec.premiumAmount()).isEqualByComparingTo("125000.0000");
        assertThat(spec.premiumAmount().scale()).isEqualTo(4);
        assertThat(spec.premiumCurrency()).isEqualTo("USD");
        assertThat(spec.expirationDate()).isEqualTo(EFFECTIVE.plusYears(1));
    }

    @Test
    void acceptsAnExplicitConversionExpiration() {
        LocalDate expiration = LocalDate.of(2028, 1, 15);

        UnderwritingSubmissionRules.ConversionSpec spec =
                UnderwritingSubmissionRules.validateConversion(submission("BOUND"),
                        new ConvertSubmissionRequest(BigDecimal.ONE, "USD", expiration));

        assertThat(spec.expirationDate()).isEqualTo(expiration);
    }

    @Test
    void rejectsInvalidConversionPremiumAndCurrency() {
        assertThatThrownBy(() -> UnderwritingSubmissionRules.validateConversion(
                submission("QUOTED"), new ConvertSubmissionRequest(null, "USD", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_CONVERSION_PREMIUM"));
        assertThatThrownBy(() -> UnderwritingSubmissionRules.validateConversion(
                submission("QUOTED"),
                new ConvertSubmissionRequest(new BigDecimal("-0.01"), "USD", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_CONVERSION_PREMIUM"));
        assertThatThrownBy(() -> UnderwritingSubmissionRules.validateConversion(
                submission("QUOTED"), new ConvertSubmissionRequest(BigDecimal.ONE, "US", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.code()).isEqualTo("INVALID_CONVERSION_CURRENCY");
                    assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                            .containsExactly("premiumCurrency");
                });
        assertThatThrownBy(() -> UnderwritingSubmissionRules.validateConversion(
                submission("QUOTED"), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_SUBMISSION_CONVERSION"));
    }

    @Test
    void rejectsAnExpirationThatIsNotAfterTheEffectiveDate() {
        assertThatThrownBy(() -> UnderwritingSubmissionRules.validateConversion(
                submission("QUOTED"),
                new ConvertSubmissionRequest(BigDecimal.ONE, "USD", EFFECTIVE)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("INVALID_CONVERSION_EXPIRATION");
                });
    }

    @Test
    void rejectsAProductCodeThatDoesNotFitThePolicyColumn() {
        UnderwritingSubmission longProduct = new UnderwritingSubmission(UUID.randomUUID(),
                UUID.fromString("11111111-1111-7111-8111-111111111111"),
                "SUB-2026-0000001", APPLICANT, "P".repeat(33), EFFECTIVE,
                new BigDecimal("2500000.0000"), "USD", List.of(), "QUOTED", null, 0,
                java.time.Instant.parse("2026-10-22T12:00:00Z"),
                java.time.Instant.parse("2026-10-22T12:00:00Z"));

        assertThatThrownBy(() -> UnderwritingSubmissionRules.validateConversion(
                longProduct, new ConvertSubmissionRequest(BigDecimal.ONE, "USD", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(
                            org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.code()).isEqualTo("PRODUCT_CODE_TOO_LONG_FOR_POLICY");
                });
    }

    @Test
    void onlyQuotedAndBoundSubmissionsAreConvertible() {
        assertThat(UnderwritingSubmissionRules.isConvertible("QUOTED")).isTrue();
        assertThat(UnderwritingSubmissionRules.isConvertible("BOUND")).isTrue();
        assertThat(UnderwritingSubmissionRules.isConvertible("RECEIVED")).isFalse();
        assertThat(UnderwritingSubmissionRules.isConvertible("REFERRED")).isFalse();
        assertThat(UnderwritingSubmissionRules.isConvertible("DECLINED")).isFalse();
        assertThat(UnderwritingSubmissionRules.isConvertible("WITHDRAWN")).isFalse();
        assertThat(UnderwritingSubmissionRules.isConvertible(null)).isFalse();
    }

    @Test
    void normalizesReferralFieldsAndOptionalNotes() {
        UnderwritingSubmissionRules.ReferralSpec spec =
                UnderwritingSubmissionRules.validateReferral(new ReferSubmissionRequest(
                        "  Aggregate limit above authority  ",
                        "  Chief Underwriting Officer  ",
                        "  Senior UW - Casualty  ",
                        "  Needs reinsurance sign-off  "));

        assertThat(spec.reason()).isEqualTo("Aggregate limit above authority");
        assertThat(spec.authority()).isEqualTo("Chief Underwriting Officer");
        assertThat(spec.referredTo()).isEqualTo("Senior UW - Casualty");
        assertThat(spec.notes()).isEqualTo("Needs reinsurance sign-off");
    }

    @Test
    void blankReferralNotesBecomeNull() {
        UnderwritingSubmissionRules.ReferralSpec spec =
                UnderwritingSubmissionRules.validateReferral(new ReferSubmissionRequest(
                        "Limit", "Authority", "Assignee", "   "));

        assertThat(spec.notes()).isNull();
    }

    @Test
    void rejectsMissingOrOverlongReferralFields() {
        assertBadRequest(() -> UnderwritingSubmissionRules.validateReferral(null),
                "INVALID_SUBMISSION_REFERRAL");
        assertBadRequest(() -> UnderwritingSubmissionRules.validateReferral(
                new ReferSubmissionRequest(" ", "Authority", "Assignee", null)),
                "INVALID_SUBMISSION_REFERRAL_REASON");
        assertBadRequest(() -> UnderwritingSubmissionRules.validateReferral(
                new ReferSubmissionRequest("R".repeat(65), "Authority", "Assignee", null)),
                "INVALID_SUBMISSION_REFERRAL_REASON");
        assertBadRequest(() -> UnderwritingSubmissionRules.validateReferral(
                new ReferSubmissionRequest("Reason", null, "Assignee", null)),
                "INVALID_SUBMISSION_REFERRAL_AUTHORITY");
        assertBadRequest(() -> UnderwritingSubmissionRules.validateReferral(
                new ReferSubmissionRequest("Reason", "A".repeat(65), "Assignee", null)),
                "INVALID_SUBMISSION_REFERRAL_AUTHORITY");
        assertBadRequest(() -> UnderwritingSubmissionRules.validateReferral(
                new ReferSubmissionRequest("Reason", "Authority", "  ", null)),
                "INVALID_SUBMISSION_REFERRAL_PARTY");
        assertBadRequest(() -> UnderwritingSubmissionRules.validateReferral(
                new ReferSubmissionRequest("Reason", "Authority", "P".repeat(129), null)),
                "INVALID_SUBMISSION_REFERRAL_PARTY");
        assertBadRequest(() -> UnderwritingSubmissionRules.validateReferral(
                new ReferSubmissionRequest("Reason", "Authority", "Assignee", "N".repeat(2001))),
                "INVALID_SUBMISSION_REFERRAL_NOTES");
    }

    @Test
    void onlyReceivedAndReferredSubmissionsAreReferrable() {
        assertThat(UnderwritingSubmissionRules.isReferrable("RECEIVED")).isTrue();
        assertThat(UnderwritingSubmissionRules.isReferrable("REFERRED")).isTrue();
        assertThat(UnderwritingSubmissionRules.isReferrable("QUOTED")).isFalse();
        assertThat(UnderwritingSubmissionRules.isReferrable("BOUND")).isFalse();
        assertThat(UnderwritingSubmissionRules.isReferrable("DECLINED")).isFalse();
        assertThat(UnderwritingSubmissionRules.isReferrable("WITHDRAWN")).isFalse();
        assertThat(UnderwritingSubmissionRules.isReferrable(null)).isFalse();
    }

    @Test
    void normalizesTheDecisionVocabulary() {
        assertThat(UnderwritingSubmissionRules.normalizeReferralDecision(" approved "))
                .isEqualTo("APPROVED");
        assertThat(UnderwritingSubmissionRules.normalizeReferralDecision("declined"))
                .isEqualTo("DECLINED");
        assertBadRequest(() -> UnderwritingSubmissionRules.normalizeReferralDecision("PENDING"),
                "INVALID_SUBMISSION_REFERRAL_DECISION");
        assertBadRequest(() -> UnderwritingSubmissionRules.normalizeReferralDecision("MAYBE"),
                "INVALID_SUBMISSION_REFERRAL_DECISION");
        assertBadRequest(() -> UnderwritingSubmissionRules.normalizeReferralDecision(null),
                "INVALID_SUBMISSION_REFERRAL_DECISION");
    }

    @Test
    void aDecisionRequiresAPendingReferralOnAReferredSubmission() {
        assertThat(UnderwritingSubmissionRules.isReferralPending(
                referralSubmission("REFERRED", "PENDING"))).isTrue();
        assertThat(UnderwritingSubmissionRules.isReferralPending(
                referralSubmission("REFERRED", "APPROVED"))).isFalse();
        assertThat(UnderwritingSubmissionRules.isReferralPending(
                referralSubmission("REFERRED", null))).isFalse();
        assertThat(UnderwritingSubmissionRules.isReferralPending(
                referralSubmission("RECEIVED", "PENDING"))).isFalse();
        assertThat(UnderwritingSubmissionRules.isReferralPending(null)).isFalse();
    }

    @Test
    void validatesDecisionFields() {
        UnderwritingSubmissionRules.ReferralDecisionSpec spec =
                UnderwritingSubmissionRules.validateReferralDecision(
                        new DecideReferralRequest(" APPROVED ", "  Chief Underwriter  ",
                                "  Approved within authority  "));
        assertThat(spec.decision()).isEqualTo("APPROVED");
        assertThat(spec.decidedBy()).isEqualTo("Chief Underwriter");
        assertThat(spec.notes()).isEqualTo("Approved within authority");

        assertBadRequest(() -> UnderwritingSubmissionRules.validateReferralDecision(null),
                "INVALID_SUBMISSION_REFERRAL_DECISION");
        assertBadRequest(() -> UnderwritingSubmissionRules.validateReferralDecision(
                new DecideReferralRequest("APPROVED", " ", null)),
                "INVALID_SUBMISSION_REFERRAL_DECIDED_BY");
        assertBadRequest(() -> UnderwritingSubmissionRules.validateReferralDecision(
                new DecideReferralRequest("APPROVED", "D".repeat(129), null)),
                "INVALID_SUBMISSION_REFERRAL_DECIDED_BY");
        assertBadRequest(() -> UnderwritingSubmissionRules.validateReferralDecision(
                new DecideReferralRequest("APPROVED", "Underwriter", "N".repeat(2001))),
                "INVALID_SUBMISSION_REFERRAL_NOTES");
    }

    private static UnderwritingSubmission referralSubmission(String status, String decision) {
        return new UnderwritingSubmission(UUID.randomUUID(),
                UUID.fromString("11111111-1111-7111-8111-111111111111"),
                "SUB-2026-0000001", APPLICANT, "CGL_PRODUCTS", EFFECTIVE,
                new BigDecimal("2500000.0000"), "USD", List.of(), status, null,
                "Limit", "Authority", "Assignee", "Notes", decision,
                "APPROVED".equals(decision) ? "Chief Underwriter" : null,
                "APPROVED".equals(decision)
                        ? java.time.Instant.parse("2026-10-22T12:00:00Z") : null,
                0, java.time.Instant.parse("2026-10-22T12:00:00Z"),
                java.time.Instant.parse("2026-10-22T12:00:00Z"));
    }

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                         String code) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status())
                            .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo(code);
                });
    }

    private static UnderwritingSubmission submission(String status) {
        return new UnderwritingSubmission(UUID.randomUUID(),
                UUID.fromString("11111111-1111-7111-8111-111111111111"),
                "SUB-2026-0000001", APPLICANT, "CGL_PRODUCTS", EFFECTIVE,
                new BigDecimal("2500000.0000"), "USD", List.of(), status, null, 0,
                java.time.Instant.parse("2026-10-22T12:00:00Z"),
                java.time.Instant.parse("2026-10-22T12:00:00Z"));
    }
}
