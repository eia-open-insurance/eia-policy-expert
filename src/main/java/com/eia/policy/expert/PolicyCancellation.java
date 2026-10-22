package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Cancellation with the pro-rata return premium computed at cancellation time.
 *
 * <p>{@code cancellationNumber} is the server-generated business reference
 * ({@code CX-{7 digits}}), unique per policy. Historical rows migrated from an
 * earlier schema may have no number; every cancellation created by the service
 * carries one.</p>
 */
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
        Instant createdAt,
        String cancellationNumber
) {
    /** Compatibility shape for historical rows that predate cancellation numbering. */
    public PolicyCancellation(UUID id, UUID tenantId, UUID policyId, UUID policyVersionId,
                              String cancellationType, String reason, LocalDate effectiveDate,
                              int noticeDays, Instant noticedAt, int termDays, int remainingDays,
                              BigDecimal proRataPct, BigDecimal earnedPremiumAmount,
                              BigDecimal returnPremiumAmount, String premiumCurrency,
                              UUID approvalRef, UUID approvedBy, String status, Instant createdAt) {
        this(id, tenantId, policyId, policyVersionId, cancellationType, reason, effectiveDate,
                noticeDays, noticedAt, termDays, remainingDays, proRataPct,
                earnedPremiumAmount, returnPremiumAmount, premiumCurrency, approvalRef,
                approvedBy, status, createdAt, null);
    }
}
