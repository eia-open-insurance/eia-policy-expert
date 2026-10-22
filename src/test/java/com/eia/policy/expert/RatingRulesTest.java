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
 * Pure rating invariants: the two methods, the documented defaults, HALF_UP
 * rounding at scale 4, the DRAFT/QUOTED lifecycle gate, input validation, and
 * the reproducible canonical hash.
 */
class RatingRulesTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID POLICY_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);

    @Test
    void rateTimesExposureMultipliesTheBaseRateTheExposureAndTheFactor() {
        RatingRules.RatingSpec spec = RatingRules.validate(
                new RatingRequest("RATE_TIMES_EXPOSURE", new BigDecimal("0.0125"),
                        new BigDecimal("1000000"), new BigDecimal("1.25"), false),
                "USD");

        assertThat(spec.ratingMethod()).isEqualTo("RATE_TIMES_EXPOSURE");
        assertThat(spec.baseRate()).isEqualByComparingTo("0.012500");
        assertThat(spec.baseRate().scale()).isEqualTo(6);
        assertThat(spec.exposureAmount()).isEqualByComparingTo("1000000.0000");
        assertThat(spec.exposureAmount().scale()).isEqualTo(4);
        assertThat(spec.factor()).isEqualByComparingTo("1.250000");
        assertThat(spec.factor().scale()).isEqualTo(6);
        assertThat(spec.computedPremium()).isEqualTo(new BigDecimal("15625.0000"));
        assertThat(spec.computedPremium().scale()).isEqualTo(4);
        assertThat(spec.currency()).isEqualTo("USD");
    }

    @Test
    void flatIgnoresTheExposureAndDefaultsTheFactorToOne() {
        RatingRules.RatingSpec spec = RatingRules.validate(
                new RatingRequest("FLAT", new BigDecimal("5000"), new BigDecimal("999999"),
                        null, null),
                "USD");

        assertThat(spec.ratingMethod()).isEqualTo("FLAT");
        assertThat(spec.exposureAmount()).isNull();
        assertThat(spec.factor()).isEqualTo(new BigDecimal("1.000000"));
        assertThat(spec.computedPremium()).isEqualTo(new BigDecimal("5000.0000"));
    }

    @Test
    void normalizesTheMethodAndCurrency() {
        RatingRules.RatingSpec spec = RatingRules.validate(
                new RatingRequest("  rate_times_exposure  ", new BigDecimal("2"),
                        new BigDecimal("10"), null, null),
                " usd ");

        assertThat(spec.ratingMethod()).isEqualTo("RATE_TIMES_EXPOSURE");
        assertThat(spec.currency()).isEqualTo("USD");
        assertThat(spec.computedPremium()).isEqualTo(new BigDecimal("20.0000"));
    }

    @Test
    void roundsTheComputedPremiumHalfUpToFourFractionDigits() {
        assertThat(RatingRules.compute("FLAT", new BigDecimal("100.000050"), null,
                BigDecimal.ONE)).isEqualTo(new BigDecimal("100.0001"));
        assertThat(RatingRules.compute("FLAT", new BigDecimal("100.000049"), null,
                BigDecimal.ONE)).isEqualTo(new BigDecimal("100.0000"));
        assertThat(RatingRules.compute("RATE_TIMES_EXPOSURE", new BigDecimal("0.123456"),
                new BigDecimal("1000000"), BigDecimal.ONE))
                .isEqualTo(new BigDecimal("123456.0000"));
    }

    @Test
    void acceptsOnlyDraftAndQuotedPolicies() {
        assertThat(RatingRules.RATEABLE_STATUSES).containsExactlyInAnyOrder("DRAFT", "QUOTED");
        RatingRules.requireRateable(policy("DRAFT"));
        RatingRules.requireRateable(policy("QUOTED"));

        assertThatThrownBy(() -> RatingRules.requireRateable(policy("BOUND")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.code()).isEqualTo("POLICY_NOT_RATEABLE");
                    assertThat(api.meta())
                            .containsEntry("sourceStatus", "BOUND")
                            .containsEntry("allowedStatuses", java.util.List.of("DRAFT", "QUOTED"));
                });
    }

    @Test
    void rejectsInvalidMethodsBaseRatesExposuresAndFactors() {
        assertCode("RATING_METHOD_REQUIRED", () -> RatingRules.validate(
                new RatingRequest("  ", new BigDecimal("1"), null, null, null), "USD"));
        assertCode("RATING_METHOD_INVALID", () -> RatingRules.validate(
                new RatingRequest("MORTALITY", new BigDecimal("1"), null, null, null), "USD"));
        assertCode("RATING_BASE_RATE_REQUIRED", () -> RatingRules.validate(
                new RatingRequest("FLAT", null, null, null, null), "USD"));
        assertCode("RATING_BASE_RATE_INVALID", () -> RatingRules.validate(
                new RatingRequest("FLAT", new BigDecimal("-1"), null, null, null), "USD"));
        assertCode("RATING_EXPOSURE_REQUIRED", () -> RatingRules.validate(
                new RatingRequest("RATE_TIMES_EXPOSURE", new BigDecimal("1"), null, null, null),
                "USD"));
        assertCode("RATING_EXPOSURE_INVALID", () -> RatingRules.validate(
                new RatingRequest("RATE_TIMES_EXPOSURE", new BigDecimal("1"),
                        BigDecimal.ZERO, null, null), "USD"));
        assertCode("RATING_EXPOSURE_INVALID", () -> RatingRules.validate(
                new RatingRequest("RATE_TIMES_EXPOSURE", new BigDecimal("1"),
                        new BigDecimal("-100"), null, null), "USD"));
        assertCode("RATING_EXPOSURE_INVALID", () -> RatingRules.validate(
                new RatingRequest("RATE_TIMES_EXPOSURE", new BigDecimal("1"),
                        new BigDecimal("0.00001"), null, null), "USD"));
        assertCode("RATING_FACTOR_INVALID", () -> RatingRules.validate(
                new RatingRequest("FLAT", new BigDecimal("1"), null, new BigDecimal("-0.5"),
                        null), "USD"));
        assertCode("RATING_PREMIUM_OUT_OF_RANGE", () -> RatingRules.validate(
                new RatingRequest("FLAT", new BigDecimal("9999999999999.999999"), null,
                        new BigDecimal("999999"), null), "USD"));
    }

    @Test
    void canonicalHashIsStableAcrossEquivalentDecimalScales() {
        RatingRules.RatingSpec coarse = RatingRules.validate(
                new RatingRequest("RATE_TIMES_EXPOSURE", new BigDecimal("100"),
                        new BigDecimal("250000"), new BigDecimal("1"), null), "USD");
        RatingRules.RatingSpec fine = RatingRules.validate(
                new RatingRequest("RATE_TIMES_EXPOSURE", new BigDecimal("100.000000"),
                        new BigDecimal("250000.0000"), new BigDecimal("1.000000"), null), "USD");

        assertThat(RatingRules.calcHash(coarse)).isEqualTo(RatingRules.calcHash(fine));
        assertThat(RatingRules.calcHash(coarse)).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(RatingRules.canonical(coarse))
                .isEqualTo("""
                        ratingMethod=RATE_TIMES_EXPOSURE
                        baseRate=100.000000
                        exposureAmount=250000.0000
                        factor=1.000000
                        computedPremium=25000000.0000
                        currency=USD
                        """);
    }

    @Test
    void canonicalHashChangesWithAnyInputOrTheMethod() {
        RatingRules.RatingSpec base = RatingRules.validate(
                new RatingRequest("RATE_TIMES_EXPOSURE", new BigDecimal("100"),
                        new BigDecimal("250000"), new BigDecimal("1"), null), "USD");
        String hash = RatingRules.calcHash(base);

        RatingRules.RatingSpec otherRate = RatingRules.validate(
                new RatingRequest("RATE_TIMES_EXPOSURE", new BigDecimal("101"),
                        new BigDecimal("250000"), new BigDecimal("1"), null), "USD");
        RatingRules.RatingSpec otherExposure = RatingRules.validate(
                new RatingRequest("RATE_TIMES_EXPOSURE", new BigDecimal("100"),
                        new BigDecimal("260000"), new BigDecimal("1"), null), "USD");
        RatingRules.RatingSpec otherFactor = RatingRules.validate(
                new RatingRequest("RATE_TIMES_EXPOSURE", new BigDecimal("100"),
                        new BigDecimal("250000"), new BigDecimal("1.1"), null), "USD");
        RatingRules.RatingSpec flat = RatingRules.validate(
                new RatingRequest("FLAT", new BigDecimal("100"), null, new BigDecimal("1"),
                        null), "USD");

        assertThat(RatingRules.calcHash(otherRate)).isNotEqualTo(hash);
        assertThat(RatingRules.calcHash(otherExposure)).isNotEqualTo(hash);
        assertThat(RatingRules.calcHash(otherFactor)).isNotEqualTo(hash);
        assertThat(RatingRules.calcHash(flat)).isNotEqualTo(hash);
        assertThat(RatingRules.canonical(flat)).contains("exposureAmount=~");
    }

    private static void assertCode(String code, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo(code);
                });
    }

    private static Policy policy(String status) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Policy(POLICY_ID, TENANT, "POL-2026-0000001", "CGL",
                UUID.fromString("22222222-2222-7222-8222-222222222222"), status, FROM, TO,
                new BigDecimal("120000.0000"), "USD", new BigDecimal("5000000.0000"), "USD",
                0, now, now);
    }
}
