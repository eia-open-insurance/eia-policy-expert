package com.eia.policy.expert;

import jakarta.validation.constraints.NotBlank;

/**
 * Status body for a product. A product moves between {@code ACTIVE} and
 * {@code RETIRED}; replaying the current status is an idempotent no-op and
 * writes no event.
 */
public record UpdateProductStatusRequest(
        @NotBlank String status
) {
}
