package com.eia.policy.expert;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancellationMathTest {
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2027, 1, 1);
    private static final BigDecimal PREMIUM = new BigDecimal("120000.0000");

    @Test
    void proRataRefundsTheUnearnedHalfOfTheTerm() {
        CancellationMath.Result result = CancellationMath.calculate("PRO_RATA", PREMIUM, FROM, TO,
                LocalDate.of(2026, 7, 2));

        assertThat(result.termDays()).isEqualTo(365);
        assertThat(result.remainingDays()).isEqualTo(183);
        assertThat(result.proRataPct()).isEqualByComparingTo("0.501370");
        assertThat(result.returnPremium()).isEqualByComparingTo("60164.4000");
        assertThat(result.earnedPremium()).isEqualByComparingTo("59835.6000");
    }

    @Test
    void flatCancellationRefundsTheWholePremium() {
        CancellationMath.Result result = CancellationMath.calculate("FLAT", PREMIUM, FROM, TO,
                LocalDate.of(2026, 7, 2));

        assertThat(result.earnedPremium()).isEqualByComparingTo("0");
        assertThat(result.returnPremium()).isEqualByComparingTo("120000");
        assertThat(result.proRataPct()).isEqualByComparingTo("1");
    }

    @Test
    void voidCancellationRefundsTheWholePremium() {
        CancellationMath.Result result = CancellationMath.calculate("VOID", PREMIUM, FROM, TO,
                LocalDate.of(2026, 3, 1));

        assertThat(result.returnPremium()).isEqualByComparingTo("120000");
    }

    @Test
    void shortRateAppliesTheFiledFactorToTheProRataRefund() {
        CancellationMath.Result result = CancellationMath.calculate("SHORT_RATE", PREMIUM, FROM, TO,
                LocalDate.of(2026, 7, 2));

        assertThat(result.returnPremium()).isEqualByComparingTo("54147.9600");
        assertThat(result.earnedPremium()).isEqualByComparingTo("65852.0400");
        assertThat(result.proRataPct()).isEqualByComparingTo("0.451233");
    }

    @Test
    void nonPaymentIsTreatedAsProRata() {
        CancellationMath.Result result = CancellationMath.calculate("NON_PAYMENT", PREMIUM, FROM, TO,
                LocalDate.of(2026, 7, 2));

        assertThat(result.returnPremium()).isEqualByComparingTo("60164.4000");
    }

    @Test
    void rejectsADateOutsideTheTerm() {
        assertThatThrownBy(() -> CancellationMath.calculate("PRO_RATA", PREMIUM, FROM, TO,
                FROM.minusDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CancellationMath.calculate("PRO_RATA", PREMIUM, FROM, TO, TO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnEmptyTerm() {
        assertThatThrownBy(() -> CancellationMath.calculate("PRO_RATA", PREMIUM, FROM, FROM,
                FROM)).isInstanceOf(IllegalArgumentException.class);
    }
}
