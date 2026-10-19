package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Endorsement record (SPEC 11.2.4 `policy_endorsement`). Every endorsement
 * issues a policy version; a backdated endorsement carries the reason and the
 * approval that allowed it.
 */
public record PolicyEndorsement(
        UUID id,
        UUID tenantId,
        UUID policyId,
        UUID policyVersionId,
        String endorsementNumber,
        int sequence,
        String formNumber,
        String formEdition,
        String endorsementType,
        LocalDate effectiveDate,
        String description,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal premiumDeltaAmount,
        String premiumDeltaCurrency,
        String coverageImpact,
        List<EndorsementCoverageChange> coverageChanges,
        boolean manuscriptFlag,
        String backdateReason,
        UUID approvalRef,
        UUID approvedBy,
        String status,
        Instant issuedAt,
        Instant createdAt
) {
}
