package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Persisted policy aggregate for the first Policy vertical slice.
 *
 * <p>{@code renewedFromPolicyId} is the nullable predecessor link (SPEC 11.2.4
 * renewal lineage). A policy created by a renewal points at the term it
 * replaced; every other policy carries {@code null}.</p>
 *
 * <p>{@code programId} is the nullable link to the commercial program catalog
 * ({@code policy_program}, SPEC 11.2.4 {@code policy.program_id}). Assignment
 * is a policy command: it bumps the aggregate version and emits
 * {@code policy.policy.program-assigned}.</p>
 */
public record Policy(
        UUID id,
        UUID tenantId,
        String policyNumber,
        String productCode,
        UUID insuredPartyId,
        String status,
        LocalDate effectiveDate,
        LocalDate expirationDate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal premiumAmount,
        String premiumCurrency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal limitAmount,
        String limitCurrency,
        int version,
        Instant createdAt,
        Instant updatedAt,
        UUID renewedFromPolicyId,
        UUID programId
) {
    /**
     * Compatibility constructor for policies that predate renewal lineage.
     * Renewal-created policies use the canonical constructor with a predecessor.
     */
    public Policy(UUID id, UUID tenantId, String policyNumber, String productCode,
                  UUID insuredPartyId, String status, LocalDate effectiveDate,
                  LocalDate expirationDate, BigDecimal premiumAmount, String premiumCurrency,
                  BigDecimal limitAmount, String limitCurrency, int version, Instant createdAt,
                  Instant updatedAt) {
        this(id, tenantId, policyNumber, productCode, insuredPartyId, status, effectiveDate,
                expirationDate, premiumAmount, premiumCurrency, limitAmount, limitCurrency,
                version, createdAt, updatedAt, null, null);
    }

    /**
     * Compatibility constructor for policies with renewal lineage but no
     * program assignment.
     */
    public Policy(UUID id, UUID tenantId, String policyNumber, String productCode,
                  UUID insuredPartyId, String status, LocalDate effectiveDate,
                  LocalDate expirationDate, BigDecimal premiumAmount, String premiumCurrency,
                  BigDecimal limitAmount, String limitCurrency, int version, Instant createdAt,
                  Instant updatedAt, UUID renewedFromPolicyId) {
        this(id, tenantId, policyNumber, productCode, insuredPartyId, status, effectiveDate,
                expirationDate, premiumAmount, premiumCurrency, limitAmount, limitCurrency,
                version, createdAt, updatedAt, renewedFromPolicyId, null);
    }
}
