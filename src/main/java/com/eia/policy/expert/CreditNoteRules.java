package com.eia.policy.expert;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure credit note invariants: the status vocabulary, the one-way transition
 * graph, and request validation against the policy.
 *
 * <p>The lifecycle is DRAFT -&gt; ISSUED -&gt; APPLIED, with CANCELLED reachable
 * from DRAFT or ISSUED. APPLIED and CANCELLED are terminal, so any later
 * transition (including a replay of the same terminal status) is a state
 * conflict. Unknown status values are request errors instead.</p>
 */
final class CreditNoteRules {
    static final String DRAFT = "DRAFT";
    static final String ISSUED = "ISSUED";
    static final String APPLIED = "APPLIED";
    static final String CANCELLED = "CANCELLED";

    static final int MAX_REASON_LENGTH = 500;
    static final int CURRENCY_LENGTH = 3;

    private static final Set<String> TARGETS = Set.of(ISSUED, APPLIED, CANCELLED);

    private CreditNoteRules() {
    }

    static CreditNoteSpec validateCreate(CreateCreditNoteRequest request, Policy policy) {
        if (request == null) {
            throw PolicyErrors.badRequest("INVALID_CREDIT_NOTE", "amount",
                    "A credit note request is required.");
        }
        BigDecimal amount = Money.of(request.amount());
        if (amount == null || amount.signum() <= 0) {
            throw PolicyErrors.badRequest("INVALID_CREDIT_NOTE_AMOUNT", "amount",
                    "amount must be greater than zero.");
        }
        String currency = normalizeCurrency(request.currency(), policy);
        String reason = normalizeReason(request.reason());
        return new CreditNoteSpec(amount, currency, reason);
    }

    /**
     * The note currency is always the policy premium currency (SPEC 10.3). The
     * comparison is case-insensitive and the stored value is upper-case.
     */
    static String normalizeCurrency(String raw, Policy policy) {
        String policyCurrency = policy.premiumCurrency() == null
                ? "" : policy.premiumCurrency().trim().toUpperCase(Locale.ROOT);
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        boolean wellFormed = value.length() == CURRENCY_LENGTH
                && value.chars().allMatch(character -> character >= 'A' && character <= 'Z');
        if (!wellFormed || !value.equals(policyCurrency)) {
            throw PolicyErrors.badRequest("CURRENCY_MISMATCH", "currency",
                    "currency must match the policy currency " + policyCurrency + ".");
        }
        return value;
    }

    /**
     * Reason is optional; a blank reason is stored as null, and the stored text
     * is trimmed and capped at 500 characters.
     */
    static String normalizeReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > MAX_REASON_LENGTH) {
            throw PolicyErrors.badRequest("INVALID_CREDIT_NOTE_REASON", "reason",
                    "reason must be at most " + MAX_REASON_LENGTH + " characters.");
        }
        return value;
    }

    static String normalizeTargetStatus(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!TARGETS.contains(value)) {
            throw PolicyErrors.badRequest("INVALID_CREDIT_NOTE_STATUS", "status",
                    "status must be ISSUED, APPLIED, or CANCELLED.");
        }
        return value;
    }

    static boolean canTransition(String currentStatus, String targetStatus) {
        if (currentStatus == null || targetStatus == null) {
            return false;
        }
        return switch (currentStatus) {
            case DRAFT -> ISSUED.equals(targetStatus) || CANCELLED.equals(targetStatus);
            case ISSUED -> APPLIED.equals(targetStatus) || CANCELLED.equals(targetStatus);
            default -> false;
        };
    }

    static boolean isTerminal(String status) {
        return APPLIED.equals(status) || CANCELLED.equals(status);
    }

    /**
     * APPLIED must name the installment that receives the credit. A missing id
     * is a request error; the tenant/policy wall is enforced by the repository
     * lookup, which reports a foreign installment as a 404.
     */
    static UUID requireInstallmentId(UUID installmentId) {
        if (installmentId == null) {
            throw PolicyErrors.badRequest("INVALID_CREDIT_NOTE_INSTALLMENT", "installmentId",
                    "installmentId is required when status is APPLIED.");
        }
        return installmentId;
    }

    /**
     * A note may only be credited against an open installment. The note amount
     * must fit inside the uncredited remainder, so the persisted
     * {@code credited_amount} never exceeds the gross amount and the receivable
     * can never become negative (SPEC BIL-06).
     */
    static void validateApply(PolicyCreditNote note, PolicyInstallment installment) {
        if (!BillingRules.isSettleable(installment.status())) {
            throw PolicyErrors.conflict("INSTALLMENT_NOT_CREDITABLE", "installmentId",
                    "Installment " + installment.id() + " is " + installment.status()
                            + " and cannot receive a credit note.",
                    Map.of("installmentId", installment.id().toString(),
                            "currentStatus", installment.status()));
        }
        BigDecimal remaining = Money.of(
                installment.amount().subtract(installment.creditedAmount()));
        BigDecimal noteAmount = Money.of(note.amount());
        if (noteAmount.compareTo(remaining) > 0) {
            throw PolicyErrors.conflict("CREDIT_NOTE_EXCEEDS_INSTALLMENT", "amount",
                    "Credit note " + note.id() + " amount " + noteAmount.toPlainString()
                            + " exceeds the installment's uncredited amount "
                            + remaining.toPlainString() + ".",
                    Map.of("creditNoteId", note.id().toString(),
                            "installmentId", installment.id().toString(),
                            "noteAmount", noteAmount.toPlainString(),
                            "availableAmount", remaining.toPlainString()));
        }
    }

    /** The persisted credited total after applying {@code noteAmount}. */
    static BigDecimal creditedAfter(PolicyInstallment installment, BigDecimal noteAmount) {
        return Money.of(installment.creditedAmount().add(noteAmount));
    }

    /**
     * Applying a note that covers the remaining amount settles the installment
     * as CREDITED; a partial credit leaves the open status untouched.
     */
    static String statusAfterCredit(PolicyInstallment installment, BigDecimal noteAmount) {
        return creditedAfter(installment, noteAmount).compareTo(installment.amount()) >= 0
                ? BillingRules.CREDITED : installment.status();
    }

    record CreditNoteSpec(BigDecimal amount, String currency, String reason) {
    }
}
