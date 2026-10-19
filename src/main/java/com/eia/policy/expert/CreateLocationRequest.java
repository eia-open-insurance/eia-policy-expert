package com.eia.policy.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Add a scheduled location to a policy. */
public record CreateLocationRequest(
        @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{0,31}",
                message = "locationCode must be an upper-case code") @Size(max = 32) String locationCode,
        @NotBlank @Size(max = 200) String addressLine1,
        @Size(max = 200) String addressLine2,
        @NotBlank @Size(max = 120) String city,
        @NotBlank @Pattern(regexp = "[A-Z]{2}-[A-Z0-9]{1,3}",
                message = "stateRegion must be an ISO 3166-2 code such as US-OH") String stateRegion,
        @Size(max = 20) String postalCode,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "country must be an ISO 3166-1 alpha-3 code")
        String country,
        @NotNull LocalDate effectiveFrom,
        @NotNull LocalDate effectiveTo
) {
}
