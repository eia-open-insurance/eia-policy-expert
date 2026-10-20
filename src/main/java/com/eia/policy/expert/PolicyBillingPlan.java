package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Billing plan for a policy (SPEC 11.2.13). A policy has at most one ACTIVE
 * plan; the total premium is split across the installments so their amounts sum
 * exactly to {@code totalPremium}.
 */
public record PolicyBillingPlan(
        UUID id,
        UUID tenantId,
        UUID policyId,
        String planNumber,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalPremium,
        String currency,
        int installmentCount,
        String frequency,
        LocalDate firstDueDate,
        String status,
        Instant createdAt
) {
}
