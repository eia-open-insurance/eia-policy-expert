package com.eia.policy.expert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Rates one policy premium.
 *
 * <p>{@code ratingMethod} is {@code RATE_TIMES_EXPOSURE} or {@code FLAT}.
 * {@code baseRate} is the rate or flat premium and must be non-negative.
 * {@code exposureAmount} is the rated exposure and is required and strictly
 * positive for {@code RATE_TIMES_EXPOSURE}; {@code FLAT} ignores it and stores
 * no exposure. {@code factor} defaults to {@code 1} and must be non-negative.
 * {@code apply} defaults to {@code false}: a calculation is always persisted as
 * a worksheet, but the policy premium only moves when the caller applies it.</p>
 */
public record RatingRequest(
        String ratingMethod,
        @NotNull(message = "baseRate is required")
        @DecimalMin(value = "0.00", message = "baseRate must be non-negative")
        @Digits(integer = 13, fraction = 6, message = "baseRate must fit numeric(19,6)")
        BigDecimal baseRate,
        @Digits(integer = 15, fraction = 4, message = "exposureAmount must fit numeric(19,4)")
        BigDecimal exposureAmount,
        @DecimalMin(value = "0.00", message = "factor must be non-negative")
        @Digits(integer = 6, fraction = 6, message = "factor must fit numeric(12,6)")
        BigDecimal factor,
        Boolean apply
) {
    /** Applies the documented defaults before validation and rating. */
    public RatingRequest {
        factor = factor == null ? BigDecimal.ONE : factor;
        apply = apply != null && apply;
    }
}
