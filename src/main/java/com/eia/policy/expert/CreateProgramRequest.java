package com.eia.policy.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Create body for the commercial program catalog. The program number, status,
 * and creation time are server-owned.
 */
public record CreateProgramRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank String programType,
        @NotNull LocalDate effectiveFrom,
        @NotNull LocalDate effectiveTo,
        UUID leadPolicyId
) {
}
