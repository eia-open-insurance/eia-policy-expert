package com.eia.policy.expert;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Underwriting submission aggregate (SPEC 11.2.14, submission intake slice).
 *
 * <p>A submission is a tenant-owned request for coverage that exists before a
 * policy. The submission number is server-generated per tenant
 * ({@code SUB-YYYY-NNNNNNN}) and immutable; the status starts at
 * {@code RECEIVED} and advances through the one-way graph in
 * {@link UnderwritingSubmissionRules}. {@code version} starts at zero and is
 * incremented by every accepted status transition.</p>
 */
public record UnderwritingSubmission(
        UUID id,
        UUID tenantId,
        String submissionNumber,
        UUID applicantPartyId,
        String productCode,
        LocalDate requestedEffectiveDate,
        BigDecimal requestedLimitAmount,
        String requestedLimitCurrency,
        List<String> tortCodes,
        String status,
        String notes,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
    public UnderwritingSubmission {
        tortCodes = tortCodes == null ? List.of() : List.copyOf(tortCodes);
    }
}
