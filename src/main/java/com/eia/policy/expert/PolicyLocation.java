package com.eia.policy.expert;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Scheduled insured location on a policy. */
public record PolicyLocation(
        UUID id,
        UUID tenantId,
        UUID policyId,
        String locationCode,
        String addressLine1,
        String addressLine2,
        String city,
        String stateRegion,
        String postalCode,
        String country,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status,
        Instant createdAt
) {
}
