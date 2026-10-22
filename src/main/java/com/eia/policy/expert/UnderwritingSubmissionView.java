package com.eia.policy.expert;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * API projection of an {@link UnderwritingSubmission}. The public contract
 * exposes the id as {@code submissionId} and never leaks the tenant column.
 */
public record UnderwritingSubmissionView(
        UUID submissionId,
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
    static UnderwritingSubmissionView of(UnderwritingSubmission submission) {
        return new UnderwritingSubmissionView(
                submission.id(),
                submission.submissionNumber(),
                submission.applicantPartyId(),
                submission.productCode(),
                submission.requestedEffectiveDate(),
                submission.requestedLimitAmount(),
                submission.requestedLimitCurrency(),
                submission.tortCodes(),
                submission.status(),
                submission.notes(),
                submission.version(),
                submission.createdAt(),
                submission.updatedAt());
    }
}
