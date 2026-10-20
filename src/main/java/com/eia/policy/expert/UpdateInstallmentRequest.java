package com.eia.policy.expert;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Settle an installment as PAID or WAIVED. Repeating the same terminal status
 * is an idempotent no-op; changing one terminal status to the other is a
 * conflict. {@code paymentId} is optional and is stored only for PAID.
 */
public record UpdateInstallmentRequest(
        @NotBlank String status,
        UUID paymentId
) {
}
