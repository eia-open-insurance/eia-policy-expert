package com.eia.policy.expert;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Endorsement invariants: SPEC POL-07 and the backdate approval gate. */
public final class EndorsementRules {
    public static final Set<String> TYPES = Set.of(
            "COVERAGE", "LIMIT", "PARTY", "PREMIUM", "RETENTION", "EXCLUSION", "TERRITORY",
            "CANCELLATION", "REINSTATEMENT", "NON_RENEWAL", "CORRECTION", "MANUSCRIPT"
    );
    public static final Set<String> CHANGE_TYPES = Set.of(
            "ADD", "REMOVE", "BROADEN", "RESTRICT", "CLARIFY"
    );

    private EndorsementRules() {
    }

    public static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw PolicyErrors.badRequest("INVALID_ENDORSEMENT_TYPE", "endorsementType",
                    "endorsementType is required.");
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(value)) {
            throw PolicyErrors.badRequest("INVALID_ENDORSEMENT_TYPE", "endorsementType",
                    "Unknown endorsement type: " + raw);
        }
        return value;
    }

    public static List<EndorsementCoverageChange> normalizeChanges(
            List<CreateEndorsementRequest.CoverageChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return List.of();
        }
        return changes.stream().map(change -> {
            String changeType = change.changeType() == null
                    ? null : change.changeType().trim().toUpperCase(Locale.ROOT);
            if (changeType == null || !CHANGE_TYPES.contains(changeType)) {
                throw PolicyErrors.badRequest("INVALID_COVERAGE_CHANGE", "coverageChanges",
                        "Unknown coverage change type: " + change.changeType());
            }
            return new EndorsementCoverageChange(
                    change.coverageCode().trim().toUpperCase(Locale.ROOT),
                    changeType,
                    change.description() == null ? null : change.description().trim());
        }).toList();
    }

    /**
     * POL-07: the endorsement date must take effect before the policy expires.
     * A date before inception is a backdate and is handled by
     * {@link #requireBackdateJustification(Policy, LocalDate, String, UUID, UUID)}.
     */
    public static void validateEffectiveDate(Policy policy, LocalDate effectiveDate) {
        if (!effectiveDate.isBefore(policy.expirationDate())) {
            throw PolicyErrors.businessRule("ENDORSEMENT_OUTSIDE_POLICY_TERM", "effectiveDate",
                    "The endorsement effective date must fall before policy expiration "
                            + policy.expirationDate() + " (SPEC POL-07).");
        }
    }

    /** A backdated endorsement needs a written reason and an approval. */
    public static void requireBackdateJustification(Policy policy, LocalDate effectiveDate,
                                                    String reason, UUID approvedBy,
                                                    UUID approvalRef) {
        if (!effectiveDate.isBefore(policy.effectiveDate())) {
            return;
        }
        if (reason == null || reason.isBlank()) {
            throw PolicyErrors.businessRule("BACKDATE_REASON_REQUIRED", "backdateReason",
                    "An endorsement backdated before policy inception requires a reason.");
        }
        if (approvedBy == null || approvalRef == null) {
            throw PolicyErrors.approvalRequired("APPROVAL_REQUIRED", "approvalRef",
                    "An endorsement backdated before policy inception requires approval.");
        }
    }

    /** Money is compared only in the policy currency (SPEC 10.3). */
    public static void validatePremiumDelta(Policy policy, BigDecimal amount, String currency) {
        if (amount == null) {
            return;
        }
        String normalized = currency == null ? null : currency.trim().toUpperCase(Locale.ROOT);
        if (normalized == null || !normalized.equals(policy.premiumCurrency())) {
            throw PolicyErrors.businessRule("CURRENCY_MISMATCH", "premiumDeltaCurrency",
                    "The premium delta currency " + currency + " does not match the policy currency "
                            + policy.premiumCurrency() + ".");
        }
    }
}
