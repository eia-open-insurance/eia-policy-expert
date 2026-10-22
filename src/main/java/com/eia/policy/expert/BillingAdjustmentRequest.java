package com.eia.policy.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Append a positive premium adjustment installment to the policy's ACTIVE
 * billing plan.
 *
 * <p>The adjustment amount is derived, not supplied: it is the policy written
 * premium less the plan total premium, so only a positive remainder can be
 * billed. A zero or negative remainder is rejected with
 * {@code NO_PREMIUM_ADJUSTMENT}; a premium decrease is a credit-note concern,
 * not an installment.</p>
 *
 * <p>{@code dueDate} is required and may not precede the policy effective date
 * (the effective date itself is accepted so an adjustment can be booked from
 * inception). {@code reason} is required and is carried on the emitted
 * {@code policy.billing-plan.adjusted} event.</p>
 */
public record BillingAdjustmentRequest(
        @NotNull LocalDate dueDate,
        @NotBlank @Size(max = 500) String reason
) {
}
