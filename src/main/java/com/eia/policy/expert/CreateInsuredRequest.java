package com.eia.policy.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/** Add an insured party to a policy. */
public record CreateInsuredRequest(
        @NotNull UUID partyId,
        @NotBlank String insuredRole,
        @NotBlank @Size(max = 300) String displayName,
        @Size(max = 32) String certificateNumber,
        @Size(max = 120) String scope,
        @NotNull LocalDate effectiveFrom,
        @NotNull LocalDate effectiveTo
) {
}
