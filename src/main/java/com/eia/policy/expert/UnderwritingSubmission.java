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
 *
 * <p>The structured referral members are all nullable. A referral records why
 * the submission was routed to a human authority, who holds it, and the
 * pending/approved/declined decision with its decider and timestamp. The
 * referral decision is independent of the lifecycle {@code status}: an
 * approval keeps the submission {@code REFERRED}, while a declination also
 * moves the status to {@code DECLINED}.</p>
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
    public UnderwritingSubmission {
        tortCodes = tortCodes == null ? List.of() : List.copyOf(tortCodes);
    }

    /**
     * Compatibility constructor for a submission with no referral details.
     * Existing call sites (notably the intake path and older tests) construct
     * the non-referral members only.
     */
    public UnderwritingSubmission(UUID id, UUID tenantId, String submissionNumber,
                                  UUID applicantPartyId, String productCode,
                                  LocalDate requestedEffectiveDate,
                                  BigDecimal requestedLimitAmount,
                                  String requestedLimitCurrency, List<String> tortCodes,
                                  String status, String notes, int version,
                                  Instant createdAt, Instant updatedAt) {
        this(id, tenantId, submissionNumber, applicantPartyId, productCode,
                requestedEffectiveDate, requestedLimitAmount, requestedLimitCurrency,
                tortCodes, status, notes, null, null, null, null, null, null, null,
                version, createdAt, updatedAt);
    }
}
