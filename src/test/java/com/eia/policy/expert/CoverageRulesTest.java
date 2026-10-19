package com.eia.policy.expert;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoverageRulesTest {
    private static final LocalDate POLICY_FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate POLICY_TO = LocalDate.of(2027, 1, 1);

    @Test
    void acceptsAValidScheduleAndNormalizesIt() {
        CoverageRules.CoverageSpec spec = CoverageRules.validate(request("usd",
                        new BigDecimal("2000000"), new BigDecimal("5000000"),
                        new BigDecimal("1000000"), new BigDecimal("5000"), new BigDecimal("0.10")),
                "USD", POLICY_FROM, POLICY_TO);

        assertThat(spec.coverageCode()).isEqualTo("GL_PRODUCTS");
        assertThat(spec.occurrenceBasis()).isEqualTo("OCCURRENCE");
        assertThat(spec.limit().currency()).isEqualTo("USD");
        assertThat(spec.limit().aggregateBasis()).isEqualTo("PER_COVERAGE");
        assertThat(spec.limit().erosionBasis()).isEqualTo("PAID_PLUS_RESERVED");
    }

    @Test
    void rejectsCoverageDatesOutsideThePolicyTerm() {
        CreateCoverageRequest request = new CreateCoverageRequest("GL_PRODUCTS", "Products",
                "A", "OCCURRENCE", POLICY_FROM.minusDays(1), POLICY_TO,
                null, limit("USD", new BigDecimal("1000000"), null, null, null, null));

        assertThatThrownBy(() -> CoverageRules.validate(request, "USD", POLICY_FROM, POLICY_TO))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("COVERAGE_OUTSIDE_POLICY_TERM"));
    }

    @Test
    void rejectsAnInvertedCoverageRange() {
        CreateCoverageRequest request = new CreateCoverageRequest("GL_PRODUCTS", "Products",
                "A", "OCCURRENCE", POLICY_TO.minusDays(1), POLICY_TO.minusDays(10),
                null, limit("USD", new BigDecimal("1000000"), null, null, null, null));

        assertThatThrownBy(() -> CoverageRules.validate(request, "USD", POLICY_FROM, POLICY_TO))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("INVALID_COVERAGE_RANGE"));
    }

    @Test
    void rejectsEachOccurrenceAboveTheAggregate() {
        CreateCoverageRequest request = request("USD",
                new BigDecimal("6000000"), new BigDecimal("5000000"), null, null, null);

        assertThatThrownBy(() -> CoverageRules.validate(request, "USD", POLICY_FROM, POLICY_TO))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.code()).isEqualTo("LIMIT_EXCEEDS_AGGREGATE");
                });
    }

    @Test
    void rejectsADeductibleAtOrAboveTheEachOccurrenceLimit() {
        CreateCoverageRequest request = request("USD", new BigDecimal("1000000"), null,
                null, new BigDecimal("1000000"), null);

        assertThatThrownBy(() -> CoverageRules.validate(request, "USD", POLICY_FROM, POLICY_TO))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("DEDUCTIBLE_EXCEEDS_LIMIT"));
    }

    @Test
    void rejectsASublimitAboveTheEachOccurrenceLimit() {
        CreateCoverageRequest request = request("USD", new BigDecimal("1000000"), null,
                new BigDecimal("2000000"), null, null);

        assertThatThrownBy(() -> CoverageRules.validate(request, "USD", POLICY_FROM, POLICY_TO))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("SUBLIMIT_EXCEEDS_LIMIT"));
    }

    @Test
    void rejectsACurrencyThatDiffersFromThePolicyCurrency() {
        CreateCoverageRequest request = request("EUR", new BigDecimal("1000000"), null,
                null, null, null);

        assertThatThrownBy(() -> CoverageRules.validate(request, "USD", POLICY_FROM, POLICY_TO))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("CURRENCY_MISMATCH"));
    }

    @Test
    void rejectsACoinsuranceFractionAboveOne() {
        CreateCoverageRequest request = request("USD", new BigDecimal("1000000"), null,
                null, null, new BigDecimal("1.5"));

        assertThatThrownBy(() -> CoverageRules.validate(request, "USD", POLICY_FROM, POLICY_TO))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("INVALID_COINSURANCE"));
    }

    @Test
    void rejectsAnOccurrenceLimitOfZero() {
        CreateCoverageRequest request = request("USD", BigDecimal.ZERO, null, null, null, null);

        assertThatThrownBy(() -> CoverageRules.validate(request, "USD", POLICY_FROM, POLICY_TO))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_PER_OCCURRENCE_LIMIT"));
    }

    @Test
    void rejectsAnUnknownOccurrenceBasis() {
        CreateCoverageRequest request = new CreateCoverageRequest("GL_PRODUCTS", "Products",
                "A", "SOMETIMES", POLICY_FROM, POLICY_TO, null,
                limit("USD", new BigDecimal("1000000"), null, null, null, null));

        assertThatThrownBy(() -> CoverageRules.validate(request, "USD", POLICY_FROM, POLICY_TO))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code()).isEqualTo("INVALID_OCCURRENCE_BASIS"));
    }

    private static CreateCoverageRequest request(String currency, BigDecimal perOccurrence,
                                                 BigDecimal aggregate, BigDecimal sublimit,
                                                 BigDecimal deductible, BigDecimal coinsurance) {
        return new CreateCoverageRequest("GL_PRODUCTS", "Products", "A", "OCCURRENCE",
                POLICY_FROM, POLICY_TO, coinsurance,
                limit(currency, perOccurrence, aggregate, sublimit, deductible, coinsurance));
    }

    private static CreateCoverageRequest.LimitSpec limit(String currency, BigDecimal perOccurrence,
                                                         BigDecimal aggregate, BigDecimal sublimit,
                                                         BigDecimal deductible,
                                                         BigDecimal coinsurance) {
        return new CreateCoverageRequest.LimitSpec(perOccurrence, aggregate, null, sublimit,
                deductible, coinsurance, currency, null);
    }
}
