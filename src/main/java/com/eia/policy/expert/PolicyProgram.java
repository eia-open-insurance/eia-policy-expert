package com.eia.policy.expert;

import java.math.BigDecimal;
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
 *
 * <p>{@code capacityAmount} and {@code capacityCurrency} are the optional
 * capacity the program can write. Capacity is a positive money amount, so the
 * pair is either both present or both absent; the currency is an ISO 4217
 * code.</p>
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
        BigDecimal capacityAmount,
        String capacityCurrency,
        UUID leadPolicyId,
        Instant createdAt
) {
    /**
     * Compatibility constructor for programs that predate capacity.
     */
    public PolicyProgram(UUID id, UUID tenantId, String programNumber, String name,
                         String programType, LocalDate effectiveFrom, LocalDate effectiveTo,
                         String status, UUID leadPolicyId, Instant createdAt) {
        this(id, tenantId, programNumber, name, programType, effectiveFrom, effectiveTo, status,
                null, null, leadPolicyId, createdAt);
    }
}
