package com.eia.policy.expert;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Target status for a credit note transition: ISSUED, APPLIED, or CANCELLED.
 * DRAFT is only the creation state and cannot be requested here.
 *
 * <p>{@code installmentId} is required for APPLIED and names the installment
 * the note is credited against. ISSUED and CANCELLED do not consume it.</p>
 */
public record UpdateCreditNoteStatusRequest(
        @NotBlank String status,
        UUID installmentId
) {
    public UpdateCreditNoteStatusRequest(String status) {
        this(status, null);
    }
}
