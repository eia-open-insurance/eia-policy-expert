package com.eia.policy.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Target status for a credit note transition: ISSUED, APPLIED, CANCELLED, or
 * REVERSED. DRAFT is only the creation state and cannot be requested here.
 *
 * <p>{@code installmentId} is required for APPLIED and names the installment
 * the note is credited against. ISSUED, CANCELLED, and REVERSED do not consume
 * it; a REVERSED note releases the credit from the installment recorded at
 * application time. {@code reason} is required for REVERSED (it explains the
 * reversal) and is capped at 500 characters; the other transitions ignore
 * it.</p>
 */
public record UpdateCreditNoteStatusRequest(
        @NotBlank String status,
        UUID installmentId,
        @Size(max = 500) String reason
) {
    public UpdateCreditNoteStatusRequest(String status) {
        this(status, null, null);
    }

    public UpdateCreditNoteStatusRequest(String status, UUID installmentId) {
        this(status, installmentId, null);
    }
}
