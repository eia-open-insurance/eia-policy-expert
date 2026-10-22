package com.eia.policy.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * API projection of one {@link SubmissionReferral} history row. The id is
 * exposed as {@code referralId} and the tenant column is never leaked, matching
 * the submission projection.
 */
public record SubmissionReferralView(
        UUID referralId,
        UUID submissionId,
        String reason,
        String authority,
        String referredTo,
        String notes,
        String decision,
        String decidedBy,
        Instant decidedAt,
        Instant createdAt
) {
    static SubmissionReferralView of(SubmissionReferral referral) {
        return new SubmissionReferralView(
                referral.id(),
                referral.submissionId(),
                referral.reason(),
                referral.authority(),
                referral.referredTo(),
                referral.notes(),
                referral.decision(),
                referral.decidedBy(),
                referral.decidedAt(),
                referral.createdAt());
    }
}
