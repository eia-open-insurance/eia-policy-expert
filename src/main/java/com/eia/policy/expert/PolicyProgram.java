package com.eia.policy.expert;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Commercial program catalog row (SPEC 11.2.4 {@code policy.program_id}).
 *
 * <p>A program groups policies into a portfolio: a captive, pool, fronting
 * arrangement, coinsurance facility, or mass tort program. The program number is
 * unique per tenant, the effective range is inclusive, and a policy may point at
 * one program through {@code policy.program_id}.</p>
 */
public record PolicyProgram(
        UUID id,
        UUID tenantId,
        String programNumber,
        String name,
        String programType,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status,
        UUID leadPolicyId,
        Instant createdAt
) {
}
