package com.eia.policy.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Referral-decision body for an underwriting submission. The decision is
 * {@code APPROVED} or {@code DECLINED}; {@code decidedBy} is required and
 * notes are optional. The command requires a pending referral.
 */
public record DecideReferralRequest(
        @NotBlank @Size(max = 32) String decision,
        @NotBlank @Size(max = 128) String decidedBy,
        @Size(max = 2000) String notes
) {
}
