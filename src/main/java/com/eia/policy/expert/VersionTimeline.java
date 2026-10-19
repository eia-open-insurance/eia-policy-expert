package com.eia.policy.expert;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Version timeline rules for SPEC 11.2.4 `policy_version` and invariants
 * POL-06/POL-07.
 *
 * <p>A version's {@code effectiveFrom} is the date on which that version's state
 * takes effect; its {@code effectiveTo} is set when a successor supersedes it.
 * The timeline is monotonic: a new version may not start before the current
 * version unless the change is an explicitly approved backdate. A backdated
 * change is recorded with the current version's start date and the retroactive
 * business date is kept on the endorsement, so two versions never claim the same
 * effective window. Two non-superseded versions covering the same date are an
 * overlap and are rejected.
 */
public final class VersionTimeline {
    public static final Set<String> CHANGE_TYPES = Set.of(
            "BIND", "ISSUE", "ENDORSEMENT", "RENEWAL", "CANCELLATION", "REINSTATEMENT",
            "CORRECTION", "PREMIUM_AUDIT", "VOID"
    );
    public static final Set<String> VERSION_STATUSES = Set.of(
            "DRAFT", "ISSUED", "VOIDED", "SUPERSEDED"
    );

    private VersionTimeline() {
    }

    /** Existing version window used by the overlap check. */
    public record Window(java.util.UUID versionId,
                         java.time.LocalDate effectiveFrom,
                         java.time.LocalDate effectiveTo,
                         String status) {
    }

    /**
     * Resolves the effective date of a new version and enforces the backdate
     * rules. A change dated before policy inception needs a reason and an
     * approval. A change dated before the current version (a regression) needs
     * the same justification; an approved regression is clamped to the current
     * version start so the stored timeline stays monotonic.
     */
    public static java.time.LocalDate resolve(java.time.LocalDate requested,
                                              Policy policy,
                                              PolicyVersion current,
                                              String backdateReason,
                                              java.util.UUID approvedBy,
                                              java.util.UUID approvalRef) {
        if (requested == null) {
            throw PolicyErrors.badRequest("VERSION_EFFECTIVE_DATE_REQUIRED", "effectiveDate",
                    "A version effective date is required.");
        }
        boolean beforeInception = requested.isBefore(policy.effectiveDate());
        boolean justified = backdateReason != null && !backdateReason.isBlank()
                && approvedBy != null && approvalRef != null;
        if (beforeInception) {
            if (backdateReason == null || backdateReason.isBlank()) {
                throw PolicyErrors.businessRule("VERSION_BACKDATE_REASON_REQUIRED", "backdateReason",
                        "A backdated change before policy inception requires a reason.");
            }
            if (approvedBy == null || approvalRef == null) {
                throw PolicyErrors.approvalRequired("APPROVAL_REQUIRED", "approvalRef",
                        "A backdated change before policy inception requires an approval reference.");
            }
        }
        if (!requested.isBefore(policy.expirationDate())) {
            throw PolicyErrors.businessRule("VERSION_OUTSIDE_POLICY_TERM", "effectiveDate",
                    "A version effective date must fall inside the policy term "
                            + policy.effectiveDate() + " to " + policy.expirationDate() + ".");
        }
        java.time.LocalDate resolved = requested;
        if (beforeInception && justified) {
            resolved = policy.effectiveDate();
        }
        if (current != null && resolved.isBefore(current.effectiveFrom())) {
            if (!justified) {
                throw PolicyErrors.businessRule("VERSION_REGRESSION", "effectiveDate",
                        "The change is dated before version " + current.versionNumber()
                                + " and would regress the version timeline; a backdate reason and"
                                + " approval are required.");
            }
            resolved = current.effectiveFrom();
        }
        return resolved;
    }

    /** Rejects an inverted or out-of-term explicit version window. */
    public static void validateExplicitRange(java.time.LocalDate effectiveFrom,
                                             java.time.LocalDate effectiveTo,
                                             java.time.LocalDate policyTo) {
        if (effectiveTo == null) {
            return;
        }
        if (!effectiveTo.isAfter(effectiveFrom)) {
            throw PolicyErrors.businessRule("INVALID_VERSION_RANGE", "effectiveTo",
                    "A version effectiveTo must be after its effectiveFrom.");
        }
        if (effectiveTo.isAfter(policyTo)) {
            throw PolicyErrors.businessRule("VERSION_OUTSIDE_POLICY_TERM", "effectiveTo",
                    "A version effectiveTo must not extend past policy expiration " + policyTo + ".");
        }
    }

    /**
     * Rejects overlap between the proposed window and any other non-superseded
     * version. The version being superseded is excluded because it is closed by
     * this change.
     */
    public static void validateNoOverlap(java.time.LocalDate effectiveFrom,
                                         java.time.LocalDate effectiveTo,
                                         java.time.LocalDate policyTo,
                                         java.util.UUID supersededVersionId,
                                         List<Window> existing) {
        if (effectiveFrom == null || existing == null || existing.isEmpty()) {
            return;
        }
        java.time.LocalDate proposedTo = effectiveTo == null ? policyTo : effectiveTo;
        for (Window window : existing) {
            if (window.versionId() != null && window.versionId().equals(supersededVersionId)) {
                continue;
            }
            if ("SUPERSEDED".equals(window.status())) {
                continue;
            }
            java.time.LocalDate start = window.effectiveFrom();
            java.time.LocalDate end = window.effectiveTo() == null ? policyTo : window.effectiveTo();
            if (start == null) {
                continue;
            }
            if (effectiveFrom.isBefore(end) && start.isBefore(proposedTo)) {
                throw PolicyErrors.conflict("VERSION_RANGE_OVERLAP", "effectiveDate",
                        "The version window overlaps version starting " + start
                                + "; close or supersede the overlapping version first.");
            }
        }
    }

    public static String normalizeChangeType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw PolicyErrors.badRequest("INVALID_CHANGE_TYPE", "changeType",
                    "changeType is required.");
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!CHANGE_TYPES.contains(value)) {
            throw PolicyErrors.badRequest("INVALID_CHANGE_TYPE", "changeType",
                    "Unknown policy version change type: " + raw);
        }
        return value;
    }
}
