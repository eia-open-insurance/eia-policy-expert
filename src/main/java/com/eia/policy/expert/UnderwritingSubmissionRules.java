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
 *
 * <p>A structured referral is a separate command from the lifecycle graph. It
 * is accepted only while the submission is {@code RECEIVED} or
 * {@code REFERRED}, stores the reason/authority/assignee plus optional notes,
 * and starts the decision at {@code PENDING}. A decision requires that
 * {@code PENDING} state; {@code APPROVED} leaves the submission
 * {@code REFERRED}, while {@code DECLINED} also moves the lifecycle to
 * {@code DECLINED}.</p>
 */
final class UnderwritingSubmissionRules {
    static final int MAX_PRODUCT_CODE_LENGTH = 64;
    static final int MAX_TORT_CODES = 20;
    static final int MAX_TORT_CODE_LENGTH = 32;
    static final int MAX_NOTES_LENGTH = 2000;
    static final int MAX_REASON_LENGTH = 500;
    static final int CURRENCY_LENGTH = 3;
    static final int MAX_REFERRAL_REASON_LENGTH = 64;
    static final int MAX_REFERRAL_AUTHORITY_LENGTH = 64;
    static final int MAX_REFERRED_TO_LENGTH = 128;
    static final int MAX_REFERRAL_NOTES_LENGTH = 2000;
    static final int MAX_REFERRAL_DECIDED_BY_LENGTH = 128;

    static final String RECEIVED = "RECEIVED";
    static final String REFERRED = "REFERRED";
    static final String QUOTED = "QUOTED";
    static final String BOUND = "BOUND";
    static final String DECLINED = "DECLINED";
    static final String WITHDRAWN = "WITHDRAWN";

    static final String REFERRAL_PENDING = "PENDING";
    static final String REFERRAL_APPROVED = "APPROVED";

    private static final List<String> STATUSES =
            List.of(RECEIVED, REFERRED, QUOTED, BOUND, DECLINED, WITHDRAWN);
    private static final Set<String> STATUS_SET = Set.copyOf(STATUSES);
    private static final List<String> REFERRAL_DECISIONS =
            List.of(REFERRAL_APPROVED, DECLINED);
    private static final Set<String> REFERRAL_DECISION_SET =
            Set.copyOf(REFERRAL_DECISIONS);

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

    /**
     * A referral can be opened or re-opened while the submission is still in
     * intake review. Every later status (QUOTED, BOUND, DECLINED, WITHDRAWN) is
     * past the referral stage and is a state conflict.
     */
    static boolean isReferrable(String status) {
        return RECEIVED.equals(status) || REFERRED.equals(status);
    }

    /**
     * A decision is accepted only for a referral that is currently pending and
     * still holds the submission in {@code REFERRED}.
     */
    static boolean isReferralPending(UnderwritingSubmission submission) {
        return submission != null
                && REFERRED.equals(submission.status())
                && REFERRAL_PENDING.equals(submission.referralDecision());
    }

    /**
     * Normalizes and validates one referral body. The reason, authority, and
     * assignee are required and capped at 64/64/128 characters; notes are
     * optional and capped at 2000.
     */
    static ReferralSpec validateReferral(ReferSubmissionRequest request) {
        if (request == null) {
            throw PolicyErrors.badRequest("INVALID_SUBMISSION_REFERRAL", "reason",
                    "A referral request is required.");
        }
        String reason = requiredReferralField(request.reason(),
                MAX_REFERRAL_REASON_LENGTH, "INVALID_SUBMISSION_REFERRAL_REASON", "reason");
        String authority = requiredReferralField(request.authority(),
                MAX_REFERRAL_AUTHORITY_LENGTH, "INVALID_SUBMISSION_REFERRAL_AUTHORITY",
                "authority");
        String referredTo = requiredReferralField(request.referredTo(),
                MAX_REFERRED_TO_LENGTH, "INVALID_SUBMISSION_REFERRAL_PARTY", "referredTo");
        return new ReferralSpec(reason, authority, referredTo,
                normalizeReferralNotes(request.notes()));
    }

    /**
     * Normalizes and validates one referral-decision body. The decision is
     * {@code APPROVED} or {@code DECLINED}; {@code decidedBy} is required and
     * capped at 128 characters; notes are optional and capped at 2000.
     */
    static ReferralDecisionSpec validateReferralDecision(DecideReferralRequest request) {
        if (request == null) {
            throw PolicyErrors.badRequest("INVALID_SUBMISSION_REFERRAL_DECISION", "decision",
                    "A referral decision request is required.");
        }
        String decision = normalizeReferralDecision(request.decision());
        String decidedBy = requiredReferralField(request.decidedBy(),
                MAX_REFERRAL_DECIDED_BY_LENGTH, "INVALID_SUBMISSION_REFERRAL_DECIDED_BY",
                "decidedBy");
        return new ReferralDecisionSpec(decision, decidedBy,
                normalizeReferralNotes(request.notes()));
    }

    /**
     * A referred-to assignee may be a person or a desk name, so it is kept as
     * free text rather than a party UUID.
     */
    private static String requiredReferralField(String raw, int maxLength, String code,
                                                String field) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw PolicyErrors.badRequest(code, field, field + " is required.");
        }
        if (value.length() > maxLength) {
            throw PolicyErrors.badRequest(code, field,
                    field + " must be at most " + maxLength + " characters.");
        }
        return value;
    }

    /**
     * Referral notes are optional on both commands. A blank value is stored as
     * null and omitted from the event payload.
     */
    static String normalizeReferralNotes(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > MAX_REFERRAL_NOTES_LENGTH) {
            throw PolicyErrors.badRequest("INVALID_SUBMISSION_REFERRAL_NOTES", "notes",
                    "notes must be at most " + MAX_REFERRAL_NOTES_LENGTH + " characters.");
        }
        return value;
    }

    /**
     * Normalizes one decision value. PENDING is not a decidable target, and an
     * unknown value is a request error rather than a state conflict.
     */
    static String normalizeReferralDecision(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!REFERRAL_DECISION_SET.contains(value)) {
            throw PolicyErrors.badRequest("INVALID_SUBMISSION_REFERRAL_DECISION", "decision",
                    "decision must be one of "
                            + String.join(", ", REFERRAL_DECISIONS) + ".");
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

    /** Normalized referral reason, authority, assignee, and optional notes. */
    record ReferralSpec(
            String reason,
            String authority,
            String referredTo,
            String notes
    ) {
    }

    /** Normalized decision, decider, and optional decision notes. */
    record ReferralDecisionSpec(
            String decision,
            String decidedBy,
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
