package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Reinstatement of a cancelled policy, backed by approval and premium evidence. */
public record PolicyReinstatement(
        UUID id,
        UUID tenantId,
        UUID policyId,
        UUID cancellationId,
        UUID policyVersionId,
        LocalDate effectiveDate,
        String reason,
        UUID approvalRef,
        UUID approvedBy,
        String premiumEvidenceRef,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal premiumEvidenceAmount,
        String premiumEvidenceCurrency,
        String status,
        Instant createdAt
) {
}
