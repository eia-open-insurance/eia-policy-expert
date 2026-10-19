package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Derived erosion ledger for a limit (SPEC 11.2.4 `policy_limit_state`).
 * remaining = original + reinstated - incurred + recovered (SPEC POL-11).
 */
public record PolicyLimitState(
        UUID id,
        UUID tenantId,
        UUID policyId,
        UUID limitId,
        Instant asOf,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal originalAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal paidAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reservedAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal incurredAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal recoveredAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal remainingAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal erodedPct,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reinstatedAmount,
        long sourceVersion,
        Instant computedAt,
        String reconciliationStatus,
        int version
) {
}
