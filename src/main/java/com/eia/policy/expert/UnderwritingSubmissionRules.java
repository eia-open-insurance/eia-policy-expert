package com.eia.policy.expert;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Pure underwriting submission invariants: the status vocabulary, the one-way
 * transition graph, request normalization, and the create caps.
 *
 * <p>The lifecycle is RECEIVED -&gt; REFERRED/QUOTED/DECLINED/WITHDRAWN,
 * REFERRED -&gt; QUOTED/DECLINED/WITHDRAWN, and
 * QUOTED -&gt; BOUND/DECLINED/WITHDRAWN. BOUND, DECLINED, and WITHDRAWN are
 * terminal, so any later transition (including a replay of the same terminal
 * status) is a state conflict. An unknown status value is a request error
 * instead.</p>
 *
 * <p>The database repeats the status, non-negative limit, and currency checks
 * as a backstop.</p>
 */
final class UnderwritingSubmissionRules {
    static final int MAX_PRODUCT_CODE_LENGTH = 64;
    static final int MAX_TORT_CODES = 20;
    static final int MAX_TORT_CODE_LENGTH = 32;
    static final int MAX_NOTES_LENGTH = 2000;
    static final int MAX_REASON_LENGTH = 500;
    static final int CURRENCY_LENGTH = 3;

    static final String RECEIVED = "RECEIVED";
    static final String REFERRED = "REFERRED";
    static final String QUOTED = "QUOTED";
    static final String BOUND = "BOUND";
    static final String DECLINED = "DECLINED";
    static final String WITHDRAWN = "WITHDRAWN";

    private static final List<String> STATUSES =
            List.of(RECEIVED, REFERRED, QUOTED, BOUND, DECLINED, WITHDRAWN);
    private static final Set<String> STATUS_SET = Set.copyOf(STATUSES);

    private UnderwritingSubmissionRules() {
    }

    /**
     * Normalizes and validates one create body. Missing or blank required
     * fields are 400s; an overlong product code, an invalid currency, a
     * negative limit, more than twenty tort codes, a blank or overlong tort
     * code, and overlong notes are field-specific 400s.
     */
    static UnderwritingSubmissionSpec validateCreate(
            CreateUnderwritingSubmissionRequest request) {
        if (request == null) {
            throw PolicyErrors.badRequest("INVALID_SUBMISSION", "applicantPartyId",
                    "An underwriting submission request is required.");
        }
        if (request.applicantPartyId() == null) {
            throw PolicyErrors.badRequest("INVALID_SUBMISSION_APPLICANT", "applicantPartyId",
                    "applicantPartyId is required.");
        }
        String productCode = normalizeProductCode(request.productCode());
        if (request.requestedEffectiveDate() == null) {
            throw PolicyErrors.badRequest("INVALID_SUBMISSION_DATE", "requestedEffectiveDate",
                    "requestedEffectiveDate is required.");
        }
        BigDecimal requestedLimitAmount =
                normalizeLimitAmount(request.requestedLimitAmount());
        String requestedLimitCurrency =
                normalizeCurrency(request.requestedLimitCurrency());
        List<String> tortCodes = normalizeTortCodes(request.tortCodes());
        String notes = normalizeNotes(request.notes());
        return new UnderwritingSubmissionSpec(
                request.applicantPartyId(),
                productCode,
                request.requestedEffectiveDate(),
                requestedLimitAmount,
                requestedLimitCurrency,
                tortCodes,
                notes);
    }

    static String normalizeProductCode(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw PolicyErrors.badRequest("INVALID_SUBMISSION_PRODUCT_CODE", "productCode",
                    "productCode is required.");
        }
        if (value.length() > MAX_PRODUCT_CODE_LENGTH) {
            throw PolicyErrors.badRequest("INVALID_SUBMISSION_PRODUCT_CODE", "productCode",
                    "productCode must be at most " + MAX_PRODUCT_CODE_LENGTH + " characters.");
        }
        return value;
    }

    static BigDecimal normalizeLimitAmount(BigDecimal raw) {
        if (raw == null) {
            throw PolicyErrors.badRequest("INVALID_SUBMISSION_LIMIT", "requestedLimitAmount",
                    "requestedLimitAmount is required.");
        }
        if (raw.signum() < 0) {
            throw PolicyErrors.badRequest("INVALID_SUBMISSION_LIMIT", "requestedLimitAmount",
                    "requestedLimitAmount must not be negative.");
        }
        return Money.of(raw);
    }

    /**
     * A submission can be converted once it has been priced. BOUND is accepted
     * so a retry of an already-converted submission can resolve to its existing
     * policy instead of failing the state check.
     */
    static boolean isConvertible(String status) {
        return QUOTED.equals(status) || BOUND.equals(status);
    }

    /**
     * Normalizes and validates one conversion body. The submission supplies
     * the applicant, product, effective date, and limit; the body supplies the
     * premium and the optional expiration. A missing expiration defaults to one
     * year after the requested effective date. A provided expiration must be
     * strictly after the effective date so the derived coverage window is valid.
     */
    static ConversionSpec validateConversion(UnderwritingSubmission submission,
                                             ConvertSubmissionRequest request) {
        if (request == null) {
            throw PolicyErrors.badRequest("INVALID_SUBMISSION_CONVERSION", "premiumAmount",
                    "A conversion request is required.");
        }
        BigDecimal premiumAmount = normalizePremiumAmount(request.premiumAmount());
        String premiumCurrency = normalizeCurrency(request.premiumCurrency(),
                "premiumCurrency", "INVALID_CONVERSION_CURRENCY");
        // The submission product code allows 64 characters, while the policy
        // column is 32. Reject the mismatch as a business rule rather than
        // letting the policy insert fail at the database.
        if (submission.productCode() != null && submission.productCode().length() > 32) {
            throw PolicyErrors.businessRule("PRODUCT_CODE_TOO_LONG_FOR_POLICY", "productCode",
                    "productCode must be at most 32 characters to convert to a policy.");
        }
        LocalDate expirationDate = normalizeConversionExpiration(
                submission.requestedEffectiveDate(), request.expirationDate());
        return new ConversionSpec(premiumAmount, premiumCurrency, expirationDate);
    }

    static BigDecimal normalizePremiumAmount(BigDecimal raw) {
        if (raw == null) {
            throw PolicyErrors.badRequest("INVALID_CONVERSION_PREMIUM", "premiumAmount",
                    "premiumAmount is required.");
        }
        if (raw.signum() < 0) {
            throw PolicyErrors.badRequest("INVALID_CONVERSION_PREMIUM", "premiumAmount",
                    "premiumAmount must not be negative.");
        }
        return Money.of(raw);
    }

    /**
     * The default term is one year from the requested effective date. An
     * explicit expiration must be after the effective date, matching the
     * coverage window rule used by the derived base coverage.
     */
    static LocalDate normalizeConversionExpiration(LocalDate effectiveDate,
                                                   LocalDate expirationDate) {
        LocalDate expiration = expirationDate == null
                ? effectiveDate.plusYears(1)
                : expirationDate;
        if (!expiration.isAfter(effectiveDate)) {
            throw PolicyErrors.badRequest("INVALID_CONVERSION_EXPIRATION", "expirationDate",
                    "expirationDate must be after the requested effective date "
                            + effectiveDate + ".");
        }
        return expiration;
    }

    /**
     * The currency must be a three-letter ISO 4217 code. It is trimmed and
     * stored upper-case, matching the policy money contract (SPEC 10.3).
     */
    static String normalizeCurrency(String raw) {
        return normalizeCurrency(raw, "requestedLimitCurrency",
                "INVALID_SUBMISSION_CURRENCY");
    }

    private static String normalizeCurrency(String raw, String field, String code) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        boolean wellFormed = value.length() == CURRENCY_LENGTH
                && value.chars().allMatch(character -> character >= 'A' && character <= 'Z');
        if (!wellFormed) {
            throw PolicyErrors.badRequest(code, field,
                    field + " must be a three-letter ISO 4217 code.");
        }
        return value;
    }

    /**
     * Tort codes are optional. A missing list becomes an empty list, each code
     * is trimmed, and the list is capped at twenty entries of at most 32
     * characters each. Duplicate codes are preserved in submission order.
     */
    static List<String> normalizeTortCodes(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw.size() > MAX_TORT_CODES) {
            throw PolicyErrors.badRequest("INVALID_SUBMISSION_TORT_CODES", "tortCodes",
                    "tortCodes must contain at most " + MAX_TORT_CODES + " entries.");
        }
        List<String> normalized = new ArrayList<>(raw.size());
        for (String code : raw) {
            String value = code == null ? "" : code.trim();
            if (value.isEmpty()) {
                throw PolicyErrors.badRequest("INVALID_SUBMISSION_TORT_CODES", "tortCodes",
                        "tortCodes must not contain a blank code.");
            }
            if (value.length() > MAX_TORT_CODE_LENGTH) {
                throw PolicyErrors.badRequest("INVALID_SUBMISSION_TORT_CODES", "tortCodes",
                        "Each tort code must be at most " + MAX_TORT_CODE_LENGTH
                                + " characters.");
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    static String normalizeNotes(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > MAX_NOTES_LENGTH) {
            throw PolicyErrors.badRequest("INVALID_SUBMISSION_NOTES", "notes",
                    "notes must be at most " + MAX_NOTES_LENGTH + " characters.");
        }
        return value;
    }

    /**
     * Normalizes one status value from a create filter or a transition body.
     * An unknown value is a request error, not a state conflict.
     */
    static String normalizeStatus(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!STATUS_SET.contains(value)) {
            throw PolicyErrors.badRequest("INVALID_SUBMISSION_STATUS", "status",
                    "status must be one of " + String.join(", ", STATUSES) + ".");
        }
        return value;
    }

    /**
     * The optional transition reason is trimmed and capped at 500 characters;
     * a blank reason is stored as null and omitted from the event payload.
     */
    static String normalizeReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > MAX_REASON_LENGTH) {
            throw PolicyErrors.badRequest("INVALID_SUBMISSION_REASON", "reason",
                    "reason must be at most " + MAX_REASON_LENGTH + " characters.");
        }
        return value;
    }

    static boolean canTransition(String currentStatus, String targetStatus) {
        if (currentStatus == null || targetStatus == null) {
            return false;
        }
        return switch (currentStatus) {
            case RECEIVED -> REFERRED.equals(targetStatus)
                    || QUOTED.equals(targetStatus)
                    || DECLINED.equals(targetStatus)
                    || WITHDRAWN.equals(targetStatus);
            case REFERRED -> QUOTED.equals(targetStatus)
                    || DECLINED.equals(targetStatus)
                    || WITHDRAWN.equals(targetStatus);
            case QUOTED -> BOUND.equals(targetStatus)
                    || DECLINED.equals(targetStatus)
                    || WITHDRAWN.equals(targetStatus);
            default -> false;
        };
    }

    static boolean isTerminal(String status) {
        return BOUND.equals(status) || DECLINED.equals(status) || WITHDRAWN.equals(status);
    }

    record UnderwritingSubmissionSpec(
            UUID applicantPartyId,
            String productCode,
            LocalDate requestedEffectiveDate,
            BigDecimal requestedLimitAmount,
            String requestedLimitCurrency,
            List<String> tortCodes,
            String notes
    ) {
    }

    /** Normalized premium, currency, and term for one conversion command. */
    record ConversionSpec(
            BigDecimal premiumAmount,
            String premiumCurrency,
            LocalDate expirationDate
    ) {
    }
}
