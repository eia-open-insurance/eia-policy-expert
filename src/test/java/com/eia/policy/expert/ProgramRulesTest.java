package com.eia.policy.expert;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProgramRulesTest {
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);

    @Test
    void normalizesProgramTypeAndName() {
        ProgramRules.ProgramSpec spec = ProgramRules.validateCreate(new CreateProgramRequest(
                "  Captive 2026  ", " captive ", FROM, TO, null));

        assertThat(spec.name()).isEqualTo("Captive 2026");
        assertThat(spec.programType()).isEqualTo("CAPTIVE");
        assertThat(spec.effectiveFrom()).isEqualTo(FROM);
        assertThat(spec.effectiveTo()).isEqualTo(TO);
        assertThat(spec.leadPolicyId()).isNull();
    }

    @Test
    void acceptsEveryProgramType() {
        for (String type : new String[]{"CAPTIVE", "POOL", "FRONTING", "COINSURANCE",
                "MASS_TORT"}) {
            assertThat(ProgramRules.validateCreate(new CreateProgramRequest(
                    "Program " + type, type, FROM, TO, null)).programType())
                    .isEqualTo(type);
        }
    }

    @Test
    void acceptsAOneDayInclusiveRange() {
        ProgramRules.ProgramSpec spec = ProgramRules.validateCreate(new CreateProgramRequest(
                "One day program", "POOL", FROM, FROM, null));

        assertThat(spec.effectiveTo()).isEqualTo(spec.effectiveFrom());
    }

    @Test
    void rejectsAnEndDateBeforeTheStartDate() {
        assertThatThrownBy(() -> ProgramRules.validateCreate(new CreateProgramRequest(
                "Backwards program", "CAPTIVE", TO, FROM, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.code()).isEqualTo("INVALID_PROGRAM_DATES");
                    assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                            .containsExactly("effectiveTo");
                });
    }

    @Test
    void rejectsUnknownProgramType() {
        assertThatThrownBy(() -> ProgramRules.validateCreate(new CreateProgramRequest(
                "Program", "SELF_INSURED", FROM, TO, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_PROGRAM_TYPE"));
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> ProgramRules.validateCreate(new CreateProgramRequest(
                "   ", "CAPTIVE", FROM, TO, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).code())
                        .isEqualTo("INVALID_PROGRAM_NAME"));
    }

    @Test
    void rejectsMissingDates() {
        assertThatThrownBy(() -> ProgramRules.validateCreate(new CreateProgramRequest(
                "Program", "CAPTIVE", null, TO, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).details().get(0).field())
                        .isEqualTo("effectiveFrom"));
    }

    @Test
    void keepsLeadPolicyIdOnTheSpec() {
        UUID lead = UUID.fromString("44444444-4444-7444-8444-444444444444");

        assertThat(ProgramRules.validateCreate(new CreateProgramRequest(
                "Program", "COINSURANCE", FROM, TO, lead)).leadPolicyId())
                .isEqualTo(lead);
    }

    @Test
    void keepsOptionalCapacityOnTheSpec() {
        ProgramRules.ProgramSpec spec = ProgramRules.validateCreate(new CreateProgramRequest(
                "Capacity Program", "CAPTIVE", FROM, TO, null,
                new BigDecimal("2000000.0000"), "USD"));

        assertThat(spec.capacityAmount()).isEqualByComparingTo("2000000.0000");
        assertThat(spec.capacityCurrency()).isEqualTo("USD");
    }

    @Test
    void acceptsAProgramWithoutCapacity() {
        ProgramRules.ProgramSpec spec = ProgramRules.validateCreate(new CreateProgramRequest(
                "No Capacity Program", "POOL", FROM, TO, null));

        assertThat(spec.capacityAmount()).isNull();
        assertThat(spec.capacityCurrency()).isNull();
    }

    @Test
    void rejectsAZeroOrNegativeCapacityAmount() {
        for (String amount : new String[]{"0", "-1", "-0.0001"}) {
            assertThatThrownBy(() -> ProgramRules.validateCreate(new CreateProgramRequest(
                    "Program", "CAPTIVE", FROM, TO, null, new BigDecimal(amount), "USD")))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException api = (ApiException) ex;
                        assertThat(api.code()).isEqualTo("INVALID_PROGRAM_CAPACITY");
                        assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                                .containsExactly("capacityAmount");
                    });
        }
    }

    @Test
    void rejectsANonIsoCurrency() {
        for (String currency : new String[]{"US", "USDD", "usd", "U1D"}) {
            assertThatThrownBy(() -> ProgramRules.validateCreate(new CreateProgramRequest(
                    "Program", "CAPTIVE", FROM, TO, null, new BigDecimal("1000000"), currency)))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> {
                        ApiException api = (ApiException) ex;
                        assertThat(api.code()).isEqualTo("INVALID_PROGRAM_CAPACITY");
                        assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                                .containsExactly("capacityCurrency");
                    });
        }
    }

    @Test
    void capacityAmountAndCurrencyMustBeSuppliedTogether() {
        assertThatThrownBy(() -> ProgramRules.validateCreate(new CreateProgramRequest(
                "Program", "CAPTIVE", FROM, TO, null, new BigDecimal("1000000"), null)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.code()).isEqualTo("INVALID_PROGRAM_CAPACITY");
                    assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                            .containsExactly("capacityCurrency");
                });

        assertThatThrownBy(() -> ProgramRules.validateCreate(new CreateProgramRequest(
                "Program", "CAPTIVE", FROM, TO, null, null, "USD")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.code()).isEqualTo("INVALID_PROGRAM_CAPACITY");
                    assertThat(api.details()).extracting(ApiException.FieldIssue::field)
                            .containsExactly("capacityAmount");
                });
    }

    @Test
    void computesUtilizationAsAPercentageRoundedToTwoDecimals() {
        assertThat(ProgramRules.utilizationPct(new BigDecimal("500000.0000"),
                new BigDecimal("2000000.0000"))).isEqualByComparingTo("25.00");
        assertThat(ProgramRules.utilizationPct(new BigDecimal("1000000"),
                new BigDecimal("3000000"))).isEqualByComparingTo("33.33");
        assertThat(ProgramRules.utilizationPct(null, new BigDecimal("2000000")))
                .isEqualByComparingTo("0.00");
        assertThat(ProgramRules.utilizationPct(new BigDecimal("500000"), null)).isNull();
    }
}
