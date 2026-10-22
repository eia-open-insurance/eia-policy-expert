package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable evidence of one rating calculation (SPEC 11.2.14 / RATE-02).
 *
 * <p>The row records the normalized rating method, base rate, exposure, factor,
 * the computed premium rounded to four fraction digits with HALF_UP, the policy
 * currency, and the canonical SHA-256 ({@link RatingRules#calcHash}) over those
 * inputs and the result. Worksheets are append-only: applying one never mutates
 * it, so a later read can reproduce the premium that was written.</p>
 *
 * <p>{@code exposureAmount} is null for {@code FLAT}. {@code status} is always
 * {@code CALCULATED}; whether the calculation was applied is observable through
 * the policy premium, its bumped aggregate version, and the
 * {@code policy.policy.rated} event.</p>
 */
public record PolicyRatingWorksheet(
        UUID id,
        UUID tenantId,
        UUID policyId,
        String ratingMethod,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal baseRate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal exposureAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal factor,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal computedPremium,
        String currency,
        String calcHash,
        String status,
        Instant createdAt,
        UUID createdBy
) {
}
