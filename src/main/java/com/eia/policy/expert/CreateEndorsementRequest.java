package com.eia.policy.expert;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Endorse a policy. A backdated endorsement (effective before policy inception)
 * requires {@code backdateReason} and an {@code approvalRef}/{@code approvedBy}.
 */
public record CreateEndorsementRequest(
        @Size(max = 32) String endorsementNumber,
        @NotBlank String endorsementType,
        @NotNull LocalDate effectiveDate,
        @NotBlank @Size(max = 2000) String description,
        @Digits(integer = 15, fraction = 4) BigDecimal premiumDeltaAmount,
        @Pattern(regexp = "[A-Z]{3}", message = "premiumDeltaCurrency must be an ISO 4217 code")
        String premiumDeltaCurrency,
        String coverageImpact,
        @Valid List<CoverageChange> coverageChanges,
        @Size(max = 500) String backdateReason,
        UUID approvedBy,
        UUID approvalRef,
        @Size(max = 32) String formNumber,
        @Size(max = 16) String formEdition
) {
    /** One coverage change carried by the endorsement. */
    public record CoverageChange(
            @NotBlank @Size(max = 32) String coverageCode,
            @NotBlank String changeType,
            @Size(max = 500) String description
    ) {
    }
}
