package com.eia.policy.expert;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money normalization for the Policy wire model: numeric(19,4) semantics with
 * HALF_UP rounding (SPEC 10.3). Percentages are decimal fractions with six
 * fraction digits.
 */
public final class Money {
    public static final int SCALE = 4;
    public static final int PERCENT_SCALE = 6;

    private Money() {
    }

    public static BigDecimal of(BigDecimal amount) {
        return amount == null ? null : amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal percent(BigDecimal fraction) {
        return fraction == null ? null : fraction.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
    }
}
