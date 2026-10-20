package com.eia.policy.expert;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure dunning invariants: the escalation-level and delivery-channel
 * vocabularies, the 2000-character notes cap, and the dunnable installment
 * states.
 *
 * <p>Dunning is allowed while the <em>effective</em> installment status is DUE
 * or OVERDUE. A stored DUE row past its due date is effectively OVERDUE; PAID
 * and WAIVED rows are terminal and reject dunning with a 409.</p>
 */
final class BillingDunningRules {
    static final int MAX_NOTES_LENGTH = 2000;

    static final String REMINDER = "REMINDER";
    static final String FIRST_NOTICE = "FIRST_NOTICE";
    static final String SECOND_NOTICE = "SECOND_NOTICE";
    static final String COLLECTIONS = "COLLECTIONS";

    static final String EMAIL = "EMAIL";
    static final String LETTER = "LETTER";
    static final String PORTAL = "PORTAL";

    static final String SENT = "SENT";
    static final String CANCELLED = "CANCELLED";

    private static final List<String> LEVELS =
            List.of(REMINDER, FIRST_NOTICE, SECOND_NOTICE, COLLECTIONS);
    private static final List<String> CHANNELS = List.of(EMAIL, LETTER, PORTAL);
    private static final Set<String> LEVEL_SET = Set.copyOf(LEVELS);
    private static final Set<String> CHANNEL_SET = Set.copyOf(CHANNELS);

    private BillingDunningRules() {
    }

    static String normalizeLevel(String raw) {
        return normalize(raw, LEVEL_SET, "INVALID_DUNNING_LEVEL", "level", LEVELS);
    }

    static String normalizeChannel(String raw) {
        return normalize(raw, CHANNEL_SET, "INVALID_DUNNING_CHANNEL", "channel", CHANNELS);
    }

    /**
     * Trims the notes and treats a blank value as absent. The length cap is
     * applied to the stored (trimmed) value, matching the controller's
     * {@code @Size} check on the raw body.
     */
    static String normalizeNotes(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_NOTES_LENGTH) {
            throw PolicyErrors.badRequest("INVALID_DUNNING_NOTES", "notes",
                    "notes must be at most " + MAX_NOTES_LENGTH + " characters.");
        }
        return value;
    }

    /**
     * A dunning action is allowed only while the effective status is DUE or
     * OVERDUE. PAID and WAIVED are terminal states.
     */
    static boolean isDunnable(String effectiveStatus) {
        return BillingRules.DUE.equals(effectiveStatus)
                || BillingRules.OVERDUE.equals(effectiveStatus);
    }

    private static String normalize(String raw, Set<String> allowed, String code, String field,
                                    List<String> values) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw PolicyErrors.badRequest(code, field,
                    field + " must be one of " + String.join(", ", values) + ".");
        }
        return value;
    }
}
