package com.eia.policy.expert;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

/**
 * Insured and location invariants: SPEC 11.2.4 `policy_insured` and the rule
 * that at least one ACTIVE named insured must exist before a policy is ISSUED.
 */
public final class PartyRules {
    public static final Set<String> INSURED_ROLES = Set.of(
            "NAMED_INSURED", "ADDITIONAL_INSURED", "INSURED_ORGANIZATION", "LOSS_PAYEE",
            "MORTGAGEE", "LENDER", "CERTIFICATE_HOLDER"
    );
    public static final Set<String> ISSUE_REQUIRED_STATUSES = Set.of("ISSUED", "IN_FORCE");

    private PartyRules() {
    }

    public static String normalizeInsuredRole(String raw) {
        if (raw == null || raw.isBlank()) {
            throw PolicyErrors.badRequest("INVALID_INSURED_ROLE", "insuredRole",
                    "insuredRole is required.");
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!INSURED_ROLES.contains(value)) {
            throw PolicyErrors.badRequest("INVALID_INSURED_ROLE", "insuredRole",
                    "Unknown insured role: " + raw);
        }
        return value;
    }

    /** A policy cannot be issued without a named insured. */
    public static void requireNamedInsuredBeforeIssue(String targetStatus, int namedInsuredCount) {
        if (ISSUE_REQUIRED_STATUSES.contains(targetStatus) && namedInsuredCount < 1) {
            throw PolicyErrors.businessRule("NAMED_INSURED_REQUIRED", "insureds",
                    "A policy requires at least one ACTIVE named insured before it can be "
                            + targetStatus + ".");
        }
    }

    public static void validateInsuredWindow(LocalDate effectiveFrom, LocalDate effectiveTo,
                                             LocalDate policyFrom, LocalDate policyTo) {
        validateWindow(effectiveFrom, effectiveTo, policyFrom, policyTo, "INSURED_OUTSIDE_POLICY_TERM",
                "Insured");
    }

    public static void validateLocationWindow(LocalDate effectiveFrom, LocalDate effectiveTo,
                                              LocalDate policyFrom, LocalDate policyTo) {
        validateWindow(effectiveFrom, effectiveTo, policyFrom, policyTo, "LOCATION_OUTSIDE_POLICY_TERM",
                "Location");
    }

    private static void validateWindow(LocalDate effectiveFrom, LocalDate effectiveTo,
                                       LocalDate policyFrom, LocalDate policyTo,
                                       String code, String subject) {
        if (!effectiveTo.isAfter(effectiveFrom)) {
            throw PolicyErrors.businessRule(code, "effectiveTo",
                    effectiveTo == null || effectiveFrom == null
                            ? subject + " effective dates are required."
                            : "effectiveTo must be after effectiveFrom.");
        }
        if (effectiveFrom.isBefore(policyFrom) || effectiveTo.isAfter(policyTo)) {
            throw PolicyErrors.businessRule(code, "effectiveFrom",
                    subject + " dates must fall inside the policy term "
                            + policyFrom + " to " + policyTo + ".");
        }
    }
}
