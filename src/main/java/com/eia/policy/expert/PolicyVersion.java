package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Immutable issued state of a policy (SPEC 11.2.4 `policy_version`).
 * {@code immutableHash} is the canonical SHA-256 of the version snapshot.
 */
public record PolicyVersion(
        UUID id,
        UUID tenantId,
        UUID policyId,
        int versionNumber,
        String policyVersionNumber,
        String changeType,
        String status,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Instant issuedAt,
        UUID issuedBy,
        UUID approvedBy,
        UUID transactionId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal premiumDeltaAmount,
        String premiumDeltaCurrency,
        String description,
        UUID supersedesVersionId,
        String immutableHash,
        Instant createdAt
) {
    public PolicyVersion withHash(String hash) {
        return new PolicyVersion(id, tenantId, policyId, versionNumber, policyVersionNumber,
                changeType, status, effectiveFrom, effectiveTo, issuedAt, issuedBy, approvedBy,
                transactionId, premiumDeltaAmount, premiumDeltaCurrency, description,
                supersedesVersionId, hash, createdAt);
    }

    public PolicyVersion issued(Instant issuedAt, UUID issuedBy, UUID approvedBy, String hash) {
        return new PolicyVersion(id, tenantId, policyId, versionNumber, policyVersionNumber,
                changeType, "ISSUED", effectiveFrom, effectiveTo, issuedAt, issuedBy, approvedBy,
                transactionId, premiumDeltaAmount, premiumDeltaCurrency, description,
                supersedesVersionId, hash, createdAt);
    }
}
