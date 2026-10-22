package com.eia.policy.expert;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeysTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID PARTY = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 1, 1);
    private static final LocalDate EXPIRATION = LocalDate.of(2027, 1, 1);

    @Test
    void normalizesOptionalKey() {
        assertThat(IdempotencyKeys.normalize(null)).isNull();
        assertThat(IdempotencyKeys.normalize("   ")).isNull();
        assertThat(IdempotencyKeys.normalize("  key-1 ")).isEqualTo("key-1");
    }

    @Test
    void acceptsAKeyAtTheMaximumLength() {
        assertThat(IdempotencyKeys.normalize("k".repeat(128))).hasSize(128);
    }

    @Test
    void rejectsOverlongKey() {
        assertThatThrownBy(() -> IdempotencyKeys.normalize("k".repeat(129)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void sameBusinessRequestHashesIdenticallyAcrossScaleAndWhitespace() {
        CreatePolicyRequest first = request("  ", "CGL", new BigDecimal("125000.0"),
                new BigDecimal("2000000"));
        CreatePolicyRequest second = request(null, " CGL ", new BigDecimal("125000.0000"),
                new BigDecimal("2000000.0000"));

        assertThat(IdempotencyKeys.requestHash(first))
                .isEqualTo(IdempotencyKeys.requestHash(second))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void differentRequestHashesDifferently() {
        CreatePolicyRequest base = request(null, "CGL", new BigDecimal("125000.00"),
                new BigDecimal("2000000.00"));
        CreatePolicyRequest changedPremium = new CreatePolicyRequest(TENANT, null, "CGL", PARTY, null,
                EFFECTIVE, EXPIRATION, new BigDecimal("125001.00"), "USD",
                new BigDecimal("2000000.00"), "USD");

        assertThat(IdempotencyKeys.requestHash(base))
                .isNotEqualTo(IdempotencyKeys.requestHash(changedPremium));
    }

    @Test
    void ratingRequestHashIsStableAcrossScaleAndWhitespace() {
        UUID policyId = UUID.fromString("33333333-3333-7333-8333-333333333333");
        RatingRequest first = new RatingRequest("RATE_TIMES_EXPOSURE",
                new BigDecimal("0.0125"), new BigDecimal("1000000"),
                new BigDecimal("1.25"), true);
        RatingRequest second = new RatingRequest(" rate_times_exposure ",
                new BigDecimal("0.012500"), new BigDecimal("1000000.0000"),
                new BigDecimal("1.250000"), Boolean.TRUE);

        assertThat(IdempotencyKeys.requestHash(policyId, first))
                .isEqualTo(IdempotencyKeys.requestHash(policyId, second))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void ratingRequestHashChangesWithEachRatingInput() {
        UUID policyId = UUID.fromString("33333333-3333-7333-8333-333333333333");
        RatingRequest base = new RatingRequest("RATE_TIMES_EXPOSURE",
                new BigDecimal("0.0125"), new BigDecimal("1000000"),
                new BigDecimal("1.25"), true);
        String baseHash = IdempotencyKeys.requestHash(policyId, base);

        assertThat(IdempotencyKeys.requestHash(policyId, new RatingRequest("FLAT",
                new BigDecimal("0.0125"), new BigDecimal("1000000"),
                new BigDecimal("1.25"), true))).isNotEqualTo(baseHash);
        assertThat(IdempotencyKeys.requestHash(policyId, new RatingRequest(
                "RATE_TIMES_EXPOSURE", new BigDecimal("0.0150"),
                new BigDecimal("1000000"), new BigDecimal("1.25"), true)))
                .isNotEqualTo(baseHash);
        assertThat(IdempotencyKeys.requestHash(policyId, new RatingRequest(
                "RATE_TIMES_EXPOSURE", new BigDecimal("0.0125"),
                new BigDecimal("2000000"), new BigDecimal("1.25"), true)))
                .isNotEqualTo(baseHash);
        assertThat(IdempotencyKeys.requestHash(policyId, new RatingRequest(
                "RATE_TIMES_EXPOSURE", new BigDecimal("0.0125"),
                new BigDecimal("1000000"), new BigDecimal("1.3"), true)))
                .isNotEqualTo(baseHash);
        assertThat(IdempotencyKeys.requestHash(policyId, new RatingRequest(
                "RATE_TIMES_EXPOSURE", new BigDecimal("0.0125"),
                new BigDecimal("1000000"), new BigDecimal("1.25"), false)))
                .isNotEqualTo(baseHash);
        assertThat(IdempotencyKeys.requestHash(UUID.randomUUID(), base))
                .isNotEqualTo(baseHash);
    }

    @Test
    void conversionRequestHashIsStableAcrossScaleAndCurrencyWhitespace() {
        UUID submissionId = UUID.fromString("44444444-4444-7444-8444-444444444444");
        ConvertSubmissionRequest first = new ConvertSubmissionRequest(
                new BigDecimal("125000.0"), "USD", null);
        ConvertSubmissionRequest second = new ConvertSubmissionRequest(
                new BigDecimal("125000.0000"), " usd ", null);

        assertThat(IdempotencyKeys.requestHash(submissionId, first))
                .isEqualTo(IdempotencyKeys.requestHash(submissionId, second))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void conversionRequestHashChangesWithPremiumExpirationOrSubmission() {
        UUID submissionId = UUID.fromString("44444444-4444-7444-8444-444444444444");
        ConvertSubmissionRequest base = new ConvertSubmissionRequest(
                new BigDecimal("125000.00"), "USD", null);
        String baseHash = IdempotencyKeys.requestHash(submissionId, base);

        assertThat(IdempotencyKeys.requestHash(submissionId,
                new ConvertSubmissionRequest(new BigDecimal("130000.00"), "USD", null)))
                .isNotEqualTo(baseHash);
        assertThat(IdempotencyKeys.requestHash(submissionId,
                new ConvertSubmissionRequest(new BigDecimal("125000.00"), "CAD", null)))
                .isNotEqualTo(baseHash);
        assertThat(IdempotencyKeys.requestHash(submissionId,
                new ConvertSubmissionRequest(new BigDecimal("125000.00"), "USD",
                        LocalDate.of(2027, 11, 1))))
                .isNotEqualTo(baseHash);
        assertThat(IdempotencyKeys.requestHash(UUID.randomUUID(), base))
                .isNotEqualTo(baseHash);
    }

    private static CreatePolicyRequest request(String policyNumber, String productCode,
                                               BigDecimal premium, BigDecimal limit) {
        return new CreatePolicyRequest(TENANT, policyNumber, productCode, PARTY, null,
                EFFECTIVE, EXPIRATION, premium, "USD", limit, "USD");
    }
}
