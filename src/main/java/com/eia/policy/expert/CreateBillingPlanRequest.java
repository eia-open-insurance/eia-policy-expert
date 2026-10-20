package com.eia.policy.expert;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Create the single ACTIVE billing plan for a policy. The plan's premium
 * currency is taken from the policy; the caller chooses only the installment
 * count, frequency, and first due date.
 */
public record CreateBillingPlanRequest(
        @NotNull @Min(1) @Max(24) Integer installmentCount,
        @NotBlank String frequency,
        @NotNull LocalDate firstDueDate
) {
}
