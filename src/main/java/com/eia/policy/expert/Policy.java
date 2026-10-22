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
 *
 * <p>{@code legalEntityId} is the nullable link to the legal entity master data
 * ({@code legal_entity}, SPEC 10.11.2 / 11.2.4 issuing legal entity). Assignment
 * is a policy command: it bumps the aggregate version and emits
 * {@code policy.policy.legal-entity-assigned}.</p>
 *
 * <p>{@code submissionId} is the nullable link to the underwriting submission
 * that was converted into this policy (SPEC 11.2.14). It is written by the
 * conversion command together with a version bump and is unique per tenant, so
 * a submission can produce at most one policy.</p>
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
        UUID programId,
        UUID legalEntityId,
        UUID submissionId
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
                version, createdAt, updatedAt, null, null, null);
    }

    /**
     * Compatibility constructor for policies with renewal lineage but no
     * program or legal entity assignment.
     */
    public Policy(UUID id, UUID tenantId, String policyNumber, String productCode,
                  UUID insuredPartyId, String status, LocalDate effectiveDate,
                  LocalDate expirationDate, BigDecimal premiumAmount, String premiumCurrency,
                  BigDecimal limitAmount, String limitCurrency, int version, Instant createdAt,
                  Instant updatedAt, UUID renewedFromPolicyId) {
        this(id, tenantId, policyNumber, productCode, insuredPartyId, status, effectiveDate,
                expirationDate, premiumAmount, premiumCurrency, limitAmount, limitCurrency,
                version, createdAt, updatedAt, renewedFromPolicyId, null, null);
    }

    /**
     * Compatibility constructor for policies with renewal lineage and a program
     * but no legal entity assignment.
     */
    public Policy(UUID id, UUID tenantId, String policyNumber, String productCode,
                  UUID insuredPartyId, String status, LocalDate effectiveDate,
                  LocalDate expirationDate, BigDecimal premiumAmount, String premiumCurrency,
                  BigDecimal limitAmount, String limitCurrency, int version, Instant createdAt,
                  Instant updatedAt, UUID renewedFromPolicyId, UUID programId) {
        this(id, tenantId, policyNumber, productCode, insuredPartyId, status, effectiveDate,
                expirationDate, premiumAmount, premiumCurrency, limitAmount, limitCurrency,
                version, createdAt, updatedAt, renewedFromPolicyId, programId, null);
    }

    /**
     * Compatibility constructor for policies that carry renewal, program, and
     * legal-entity links but were not created by a submission conversion.
     */
    public Policy(UUID id, UUID tenantId, String policyNumber, String productCode,
                  UUID insuredPartyId, String status, LocalDate effectiveDate,
                  LocalDate expirationDate, BigDecimal premiumAmount, String premiumCurrency,
                  BigDecimal limitAmount, String limitCurrency, int version, Instant createdAt,
                  Instant updatedAt, UUID renewedFromPolicyId, UUID programId,
                  UUID legalEntityId) {
        this(id, tenantId, policyNumber, productCode, insuredPartyId, status, effectiveDate,
                expirationDate, premiumAmount, premiumCurrency, limitAmount, limitCurrency,
                version, createdAt, updatedAt, renewedFromPolicyId, programId, legalEntityId,
                null);
    }
}
