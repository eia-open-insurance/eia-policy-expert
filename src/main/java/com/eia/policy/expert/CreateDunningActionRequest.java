package com.eia.policy.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Record a dunning action for an installment. {@code level} and {@code channel}
 * are normalized to upper case and checked against the closed vocabulary by
 * {@link BillingDunningRules}; {@code notes} is optional and capped at 2000
 * characters after trimming.
 */
public record CreateDunningActionRequest(
        @NotBlank String level,
        @NotBlank String channel,
        @Size(max = BillingDunningRules.MAX_NOTES_LENGTH) String notes
) {
}
