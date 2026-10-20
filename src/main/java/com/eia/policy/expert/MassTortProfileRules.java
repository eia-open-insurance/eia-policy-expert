package com.eia.policy.expert;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure mass tort profile invariants: the tort, trigger, allocation, and status
 * vocabularies, the 2000-character notes cap, coverage-block date order, and
 * the COMPLETE gate.
 *
 * <p>The COMPLETE gate mirrors the database constraint: a profile is complete
 * only when the three classification fields, the allocation notes, and both
 * coverage-block dates are present. Dates are inclusive, so a one-day block is
 * valid while an end before the start is not.</p>
 */
final class MassTortProfileRules {
    static final int MAX_ALLOCATION_NOTES_LENGTH = 2000;

    static final String ASBESTOS = "ASBESTOS";
    static final String SILICA = "SILICA";
    static final String TALC = "TALC";
    static final String PFAS = "PFAS";
    static final String OPIOIDS = "OPIOIDS";
    static final String PRODUCT_LIABILITY = "PRODUCT_LIABILITY";
    static final String ENVIRONMENTAL = "ENVIRONMENTAL";
    static final String OTHER = "OTHER";

    static final String EXPOSURE = "EXPOSURE";
    static final String MANIFESTATION = "MANIFESTATION";
    static final String INJURY = "INJURY";
    static final String REVISIONIST = "REVISIONIST";

    static final String ALL_SUMS = "ALL_SUMS";
    static final String PRO_RATA_TIME_ON_RISK = "PRO_RATA_TIME_ON_RISK";
    static final String PRO_RATA_PREMIUM = "PRO_RATA_PREMIUM";
    static final String EQUAL_SHARES = "EQUAL_SHARES";
    static final String STACKED_LIMITS = "STACKED_LIMITS";
    static final String CUSTOM_MATRIX = "CUSTOM_MATRIX";

    static final String DRAFT = "DRAFT";
    static final String COMPLETE = "COMPLETE";

    private static final List<String> TORT_TYPES = List.of(
            ASBESTOS, SILICA, TALC, PFAS, OPIOIDS, PRODUCT_LIABILITY, ENVIRONMENTAL, OTHER);
    private static final List<String> TRIGGER_TYPES = List.of(
            EXPOSURE, MANIFESTATION, INJURY, REVISIONIST);
    private static final List<String> ALLOCATION_METHODS = List.of(
            ALL_SUMS, PRO_RATA_TIME_ON_RISK, PRO_RATA_PREMIUM, EQUAL_SHARES,
            STACKED_LIMITS, CUSTOM_MATRIX);
    private static final Set<String> TORT_TYPE_SET = Set.copyOf(TORT_TYPES);
    private static final Set<String> TRIGGER_TYPE_SET = Set.copyOf(TRIGGER_TYPES);
    private static final Set<String> ALLOCATION_METHOD_SET = Set.copyOf(ALLOCATION_METHODS);
    private static final Set<String> STATUSES = Set.of(DRAFT, COMPLETE);

    private MassTortProfileRules() {
    }

    /**
     * Normalizes and validates one upsert body. A missing classification field
     * or status is a 400 with the same code the controller's bean validation
     * would use for a blank value; an unknown vocabulary member is a 400 with a
     * field-specific code.
     */
    static ProfileSpec validate(PutMassTortProfileRequest request) {
        if (request == null) {
            throw PolicyErrors.badRequest("INVALID_MASS_TORT_PROFILE", "tortType",
                    "A mass tort profile request is required.");
        }
        String tortType = normalizeTortType(request.tortType());
        String triggerType = normalizeTriggerType(request.triggerType());
        String allocationMethod = normalizeAllocationMethod(request.allocationMethod());
        String allocationNotes = normalizeNotes(request.allocationNotes());
        String status = normalizeStatus(request.status());
        LocalDate blockStart = request.coverageBlockStart();
        LocalDate blockEnd = request.coverageBlockEnd();
        requireValidBlock(blockStart, blockEnd);
        if (COMPLETE.equals(status)) {
            requireComplete(tortType, triggerType, allocationMethod, allocationNotes,
                    blockStart, blockEnd);
        }
        return new ProfileSpec(tortType, triggerType, allocationMethod, allocationNotes,
                blockStart, blockEnd, status);
    }

    static String normalizeTortType(String raw) {
        return normalize(raw, TORT_TYPE_SET, "INVALID_TORT_TYPE", "tortType", TORT_TYPES);
    }

    static String normalizeTriggerType(String raw) {
        return normalize(raw, TRIGGER_TYPE_SET, "INVALID_TRIGGER_TYPE", "triggerType",
                TRIGGER_TYPES);
    }

    static String normalizeAllocationMethod(String raw) {
        return normalize(raw, ALLOCATION_METHOD_SET, "INVALID_ALLOCATION_METHOD",
                "allocationMethod", ALLOCATION_METHODS);
    }

    static String normalizeStatus(String raw) {
        return normalize(raw, STATUSES, "INVALID_MASS_TORT_PROFILE_STATUS", "status",
                List.of(DRAFT, COMPLETE));
    }

    /**
     * Trims the notes and treats a blank value as absent. The length cap is
     * applied to the stored (trimmed) value.
     */
    static String normalizeNotes(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_ALLOCATION_NOTES_LENGTH) {
            throw PolicyErrors.badRequest("INVALID_ALLOCATION_NOTES", "allocationNotes",
                    "allocationNotes must be at most " + MAX_ALLOCATION_NOTES_LENGTH
                            + " characters.");
        }
        return value;
    }

    /**
     * The coverage block is an inclusive date range: both bounds may be the
     * same day, but the end may not precede the start. Either bound may be
     * absent while the profile is DRAFT.
     */
    static void requireValidBlock(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw PolicyErrors.badRequest("INVALID_COVERAGE_BLOCK", "coverageBlockEnd",
                    "coverageBlockEnd must not be before coverageBlockStart.");
        }
    }

    private static void requireComplete(String tortType, String triggerType,
                                        String allocationMethod, String allocationNotes,
                                        LocalDate blockStart, LocalDate blockEnd) {
        if (tortType == null) {
            throw incomplete("tortType");
        }
        if (triggerType == null) {
            throw incomplete("triggerType");
        }
        if (allocationMethod == null) {
            throw incomplete("allocationMethod");
        }
        if (allocationNotes == null) {
            throw incomplete("allocationNotes");
        }
        if (blockStart == null) {
            throw incomplete("coverageBlockStart");
        }
        if (blockEnd == null) {
            throw incomplete("coverageBlockEnd");
        }
    }

    private static ApiException incomplete(String field) {
        return PolicyErrors.badRequest("INCOMPLETE_MASS_TORT_PROFILE", field,
                "A COMPLETE mass tort profile requires " + field + ".");
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

    record ProfileSpec(
            String tortType,
            String triggerType,
            String allocationMethod,
            String allocationNotes,
            LocalDate coverageBlockStart,
            LocalDate coverageBlockEnd,
            String status
    ) {
    }
}
