package com.eia.policy.expert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An erosion fact for one limit. Facts are idempotent by
 * (limit, claim, allocation, sourceVersion) and the highest sourceVersion per
 * claim/allocation chain wins, so retries never double-count.
 */
public record ErosionRequest(
        @NotNull UUID limitId,
        @NotNull UUID claimId,
        @NotNull UUID allocationId,
        @NotNull @Min(1) Long sourceVersion,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) @Digits(integer = 15, fraction = 4)
        BigDecimal paidAmount,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) @Digits(integer = 15, fraction = 4)
        BigDecimal reservedAmount,
        @DecimalMin(value = "0.0", inclusive = true) @Digits(integer = 15, fraction = 4)
        BigDecimal recoveredAmount,
        Instant asOf
) {
}
