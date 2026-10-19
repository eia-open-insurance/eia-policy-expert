package com.eia.policy.expert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Reinstate a cancelled policy. Approval and premium evidence are mandatory
 * before coverage is restored.
 */
public record ReinstatePolicyRequest(
        @NotNull LocalDate effectiveDate,
        @NotBlank @Size(max = 500) String reason,
        @NotNull UUID approvedBy,
        @NotNull UUID approvalRef,
        @NotBlank @Size(max = 64) String premiumEvidenceRef,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 15, fraction = 4)
        BigDecimal premiumEvidenceAmount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "premiumEvidenceCurrency must be an ISO 4217 code")
        String premiumEvidenceCurrency
) {
}
