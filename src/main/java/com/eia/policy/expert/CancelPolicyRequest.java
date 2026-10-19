package com.eia.policy.expert;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Cancel a policy. The cancellation type determines the return premium:
 * flat/void refunds the full premium, pro-rata and non-payment refund the
 * unearned portion, and short-rate applies the filed short-rate factor.
 */
public record CancelPolicyRequest(
        @NotBlank String cancellationType,
        @NotBlank @Size(max = 200) String reason,
        @NotNull LocalDate effectiveDate,
        @Min(0) Integer noticeDays,
        UUID approvedBy,
        UUID approvalRef
) {
}
