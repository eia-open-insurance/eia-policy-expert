package com.eia.policy.expert;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cancellation and reinstatement invariants: SPEC POL-08 (date inside the term,
 * flat refunds the full unearned premium), POL-09 (reinstatement must not create
 * overlapping active coverage) and the approval/premium-evidence gate for
 * reinstatement.
 */
public final class CancellationRules {
    /** The final status of a cancellation that has not been reinstated or voided. */
    public static final String ACTIVE_STATUS = "ACTIVE";

    public static final Set<String> TYPES = Set.of(
            "FLAT", "PRO_RATA", "SHORT_RATE", "NON_PAYMENT", "VOID"
    );
    public static final Set<String> CANCELLABLE_STATUSES = Set.of(
            "ISSUED", "IN_FORCE", "ENDORSED"
    );
    public static final Set<String> REINSTATABLE_STATUSES = Set.of("CANCELLED");

    /**
     * A linked return-premium note still represents money the cancellation owes
     * while it is DRAFT, ISSUED, or APPLIED. CANCELLED and REVERSED are closed
     * and no longer block reinstatement.
     */
    private static final Set<String> OPEN_CREDIT_NOTE_STATUSES = Set.of(
            CreditNoteRules.DRAFT, CreditNoteRules.ISSUED, CreditNoteRules.APPLIED);

    private CancellationRules() {
    }

    public static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw PolicyErrors.badRequest("INVALID_CANCELLATION_TYPE", "cancellationType",
                    "cancellationType is required.");
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(value)) {
            throw PolicyErrors.badRequest("INVALID_CANCELLATION_TYPE", "cancellationType",
                    "Unknown cancellation type: " + raw);
        }
        return value;
    }

    public static void requireCancellable(Policy policy) {
        if (!CANCELLABLE_STATUSES.contains(policy.status())) {
            throw PolicyErrors.conflict("CANCELLATION_NOT_ALLOWED", "status",
                    "Policy status " + policy.status() + " cannot be cancelled; it must be "
                            + "ISSUED, IN_FORCE, or ENDORSED.");
        }
    }

    public static void validateEffectiveDate(LocalDate effectiveDate, Policy policy) {
        if (effectiveDate.isBefore(policy.effectiveDate())
                || !effectiveDate.isBefore(policy.expirationDate())) {
            throw PolicyErrors.businessRule("CANCELLATION_OUTSIDE_POLICY_TERM", "effectiveDate",
                    "The cancellation effective date must fall inside the policy term "
                            + policy.effectiveDate() + " to " + policy.expirationDate() + " (SPEC POL-08).");
        }
    }

    public static void validateNotice(String cancellationType, Integer noticeDays) {
        int days = noticeDays == null ? 0 : noticeDays;
        if (days < 0) {
            throw PolicyErrors.businessRule("INVALID_CANCELLATION_NOTICE", "noticeDays",
                    "noticeDays must be zero or greater.");
        }
        if ("NON_PAYMENT".equals(cancellationType) && days < 10) {
            throw PolicyErrors.businessRule("INSUFFICIENT_CANCELLATION_NOTICE", "noticeDays",
                    "A cancellation for non-payment requires at least 10 days of notice.");
        }
    }

    public static void requireReinstatable(Policy policy) {
        if (!REINSTATABLE_STATUSES.contains(policy.status())) {
            throw PolicyErrors.conflict("POLICY_NOT_CANCELLED", "status",
                    "Policy status " + policy.status() + " is not CANCELLED and cannot be reinstated.");
        }
    }

    /**
     * Reinstatement ends the cancellation and restores coverage, so the
     * cancellation's return premium must already be settled. A DRAFT or ISSUED
     * note has not been credited and an APPLIED note is already reducing a
     * receivable; reinstating first would strand the refund or double-count it.
     * Only an open note blocks (CANCELLED and REVERSED are closed), and the 409
     * detail names every open note and tells the caller to cancel or reverse
     * them before retrying.
     */
    public static void requireNoOpenCreditNotes(List<PolicyCreditNote> creditNotes,
                                                PolicyCancellation cancellation) {
        List<PolicyCreditNote> open = creditNotes == null ? List.of()
                : creditNotes.stream()
                        .filter(note -> note.status() != null
                                && OPEN_CREDIT_NOTE_STATUSES.contains(note.status()))
                        .sorted(Comparator.comparing(PolicyCreditNote::noteNumber,
                                Comparator.nullsLast(String::compareTo)))
                        .toList();
        if (open.isEmpty()) {
            return;
        }
        String noteList = open.stream()
                .map(note -> note.noteNumber() + " (" + note.status() + ")")
                .collect(Collectors.joining(", "));
        String cancellationRef = cancellation.cancellationNumber() == null
                ? cancellation.id().toString() : cancellation.cancellationNumber();
        String guidance = "Cancel or reverse these credit notes first, then reinstate the policy.";
        String message = "Cancellation " + cancellationRef + " has open credit notes "
                + noteList + ". " + guidance;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("cancellationId", cancellation.id().toString());
        if (cancellation.cancellationNumber() != null) {
            meta.put("cancellationNumber", cancellation.cancellationNumber());
        }
        meta.put("noteNumbers", open.stream().map(PolicyCreditNote::noteNumber).toList());
        meta.put("guidance", "Cancel or reverse the open credit notes before reinstatement.");
        throw PolicyErrors.conflict("CANCELLATION_CREDIT_NOTE_OPEN", "creditNotes",
                message, meta);
    }

    public static void requireApproval(UUID approvedBy, UUID approvalRef) {
        if (approvedBy == null || approvalRef == null) {
            throw PolicyErrors.approvalRequired("APPROVAL_REQUIRED", "approvalRef",
                    "Reinstatement requires an approval reference and approver (SPEC POL-09).");
        }
    }

    public static void requirePremiumEvidence(String evidenceRef, BigDecimal evidenceAmount) {
        if (evidenceRef == null || evidenceRef.isBlank()) {
            throw PolicyErrors.businessRule("PREMIUM_EVIDENCE_REQUIRED", "premiumEvidenceRef",
                    "Reinstatement requires documented premium evidence.");
        }
        if (evidenceAmount == null || evidenceAmount.signum() <= 0) {
            throw PolicyErrors.businessRule("PREMIUM_EVIDENCE_REQUIRED", "premiumEvidenceAmount",
                    "Reinstatement requires a premium evidence amount greater than zero.");
        }
    }

    public static void validateReinstatementDate(LocalDate effectiveDate, Policy policy,
                                                 PolicyCancellation cancellation) {
        if (effectiveDate.isBefore(policy.effectiveDate())
                || !effectiveDate.isBefore(policy.expirationDate())) {
            throw PolicyErrors.businessRule("REINSTATEMENT_OUTSIDE_POLICY_TERM", "effectiveDate",
                    "The reinstatement effective date must fall inside the policy term.");
        }
        if (effectiveDate.isBefore(cancellation.effectiveDate())) {
            throw PolicyErrors.businessRule("REINSTATEMENT_BEFORE_CANCELLATION", "effectiveDate",
                    "Reinstatement cannot take effect before the cancellation date "
                            + cancellation.effectiveDate() + ".");
        }
    }

    public static void requireSameCurrency(String evidenceCurrency, String policyCurrency) {
        String normalized = evidenceCurrency == null ? null : evidenceCurrency.trim().toUpperCase(Locale.ROOT);
        if (normalized == null || !normalized.equals(policyCurrency)) {
            throw PolicyErrors.businessRule("CURRENCY_MISMATCH", "premiumEvidenceCurrency",
                    "The premium evidence currency " + evidenceCurrency
                            + " does not match the policy currency " + policyCurrency + ".");
        }
    }
}
