package com.eia.policy.expert;

import jakarta.validation.constraints.NotBlank;

/**
 * Target status for a credit note transition: ISSUED, APPLIED, or CANCELLED.
 * DRAFT is only the creation state and cannot be requested here.
 */
public record UpdateCreditNoteStatusRequest(@NotBlank String status) {
}
