package com.eia.policy.expert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Create a DRAFT credit note. The amount is a positive money value and the
 * currency must equal the policy premium currency; the reason is optional but
 * capped at 500 characters.
 *
 * <p>{@code cancellationId} is optional. When supplied, the note is a
 * return-premium credit for that cancellation: the cancellation must belong to
 * the same tenant and policy and still be ACTIVE, and the amount and currency
 * must match the cancellation's return premium.</p>
 */
public record CreateCreditNoteRequest(
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @Size(max = 500) String reason,
        UUID cancellationId
) {
    /** Compatibility shape for a general (unlinked) billing adjustment. */
    public CreateCreditNoteRequest(BigDecimal amount, String currency, String reason) {
        this(amount, currency, reason, null);
    }
}
