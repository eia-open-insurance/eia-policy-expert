package com.eia.policy.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create body for the legal entity catalog. The id, status, and creation time
 * are server-owned.
 */
public record CreateLegalEntityRequest(
        @NotBlank @Size(max = 32) String code,
        @NotBlank @Size(max = 200) String name,
        @NotBlank String entityType,
        @NotBlank @Size(max = 2) String jurisdiction,
        @Size(max = 120) String regulator,
        @Size(max = 80) String licenseNumber
) {
}
