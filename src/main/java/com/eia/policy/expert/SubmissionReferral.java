package com.eia.policy.expert;

import java.time.Instant;
import java.util.UUID;

/**
 * One review round of an underwriting submission referral.
 *
 * <p>The submission row keeps the latest referral in its {@code referral_*}
 * columns. This record is the append-only history behind that projection:
 * every accepted refer command inserts a new round with a {@code PENDING}
 * decision, and the decision command updates the newest {@code PENDING} row
 * with the decider and instant. The history is tenant-owned like the parent
 * submission, so every read is tenant-scoped.</p>
 *
 * <p>{@code notes} is the referral narrative when the round is opened. A
 * non-blank decision note replaces it, matching the submission projection's
 * {@code referral_notes} column.</p>
 */
public record SubmissionReferral(
        UUID id,
        UUID tenantId,
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
}
