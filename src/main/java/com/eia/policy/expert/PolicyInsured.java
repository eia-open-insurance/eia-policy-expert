package com.eia.policy.expert;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Insured party on a policy (SPEC 11.2.4 `policy_insured`). */
public record PolicyInsured(
        UUID id,
        UUID tenantId,
        UUID policyId,
        UUID partyId,
        String insuredRole,
        String displayName,
        String certificateNumber,
        String scope,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status,
        Instant createdAt
) {
}
