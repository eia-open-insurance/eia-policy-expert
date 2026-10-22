package com.eia.policy.expert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Create body for an underwriting submission. The id, submission number,
 * status, version, and timestamps are server-owned.
 */
public record CreateUnderwritingSubmissionRequest(
        @NotNull UUID applicantPartyId,
        @NotBlank @Size(max = 64) String productCode,
        @NotNull LocalDate requestedEffectiveDate,
        @NotNull @DecimalMin("0.0") BigDecimal requestedLimitAmount,
        @NotBlank @Size(min = 3, max = 3) String requestedLimitCurrency,
        @Size(max = 20) List<@NotBlank @Size(max = 32) String> tortCodes,
        @Size(max = 2000) String notes
) {
}
