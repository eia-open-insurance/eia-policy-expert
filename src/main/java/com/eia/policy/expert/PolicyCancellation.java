package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Cancellation with the pro-rata return premium computed at cancellation time. */
public record PolicyCancellation(
        UUID id,
        UUID tenantId,
        UUID policyId,
        UUID policyVersionId,
        String cancellationType,
        String reason,
        LocalDate effectiveDate,
        int noticeDays,
        Instant noticedAt,
        int termDays,
        int remainingDays,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal proRataPct,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal earnedPremiumAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal returnPremiumAmount,
        String premiumCurrency,
        UUID approvalRef,
        UUID approvedBy,
        String status,
        Instant createdAt
) {
}
