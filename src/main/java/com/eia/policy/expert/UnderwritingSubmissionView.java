package com.eia.policy.expert;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * API projection of an {@link UnderwritingSubmission}. The public contract
 * exposes the id as {@code submissionId} and never leaks the tenant column.
 * {@code policyId} is the nullable link to the policy created by conversion, so
 * a client can navigate from an accepted submission to its policy.
 *
 * <p>The referral members are null until a structured referral is opened. A
 * pending referral exposes its reason, authority, assignee, and notes with
 * {@code referralDecision=PENDING}; a decision adds the decider and timestamp
 * and moves the decision to {@code APPROVED} or {@code DECLINED}.</p>
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
        UUID policyId,
        String notes,
        String referralReason,
        String referralAuthority,
        String referredTo,
        String referralNotes,
        String referralDecision,
        String referralDecidedBy,
        Instant referralDecidedAt,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
    /** Compatibility constructor for projections with no converted policy. */
    public UnderwritingSubmissionView(UUID submissionId, String submissionNumber,
                                      UUID applicantPartyId, String productCode,
                                      LocalDate requestedEffectiveDate,
                                      BigDecimal requestedLimitAmount,
                                      String requestedLimitCurrency, List<String> tortCodes,
                                      String status, String notes, int version,
                                      Instant createdAt, Instant updatedAt) {
        this(submissionId, submissionNumber, applicantPartyId, productCode,
                requestedEffectiveDate, requestedLimitAmount, requestedLimitCurrency, tortCodes,
                status, null, notes, null, null, null, null, null, null, null, version,
                createdAt, updatedAt);
    }

    /** Compatibility constructor for a pre-referral projection with a policy. */
    public UnderwritingSubmissionView(UUID submissionId, String submissionNumber,
                                      UUID applicantPartyId, String productCode,
                                      LocalDate requestedEffectiveDate,
                                      BigDecimal requestedLimitAmount,
                                      String requestedLimitCurrency, List<String> tortCodes,
                                      String status, UUID policyId, String notes, int version,
                                      Instant createdAt, Instant updatedAt) {
        this(submissionId, submissionNumber, applicantPartyId, productCode,
                requestedEffectiveDate, requestedLimitAmount, requestedLimitCurrency, tortCodes,
                status, policyId, notes, null, null, null, null, null, null, null, version,
                createdAt, updatedAt);
    }

    static UnderwritingSubmissionView of(UnderwritingSubmission submission) {
        return of(submission, null);
    }

    static UnderwritingSubmissionView of(UnderwritingSubmission submission, UUID policyId) {
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
                policyId,
                submission.notes(),
                submission.referralReason(),
                submission.referralAuthority(),
                submission.referredTo(),
                submission.referralNotes(),
                submission.referralDecision(),
                submission.referralDecidedBy(),
                submission.referralDecidedAt(),
                submission.version(),
                submission.createdAt(),
                submission.updatedAt());
    }
}
