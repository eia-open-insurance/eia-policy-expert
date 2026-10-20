package com.eia.policy.expert;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renewal invariants (SPEC 11.2.4 renewal lineage, SPEC 11.2.10 renew command).
 *
 * <p>Only an issued or in-force term can be renewed: {@code ISSUED},
 * {@code IN_FORCE}, {@code ENDORSED}, or {@code REINSTATED}. The renewal becomes
 * a new DRAFT policy, so the source term stays untouched and all inherited
 * fields are resolved here once, before any row is written.</p>
 */
public final class PolicyRenewalRules {
    public static final Set<String> RENEWABLE_STATUSES = Set.of(
            "ISSUED", "IN_FORCE", "ENDORSED", "REINSTATED");

    private PolicyRenewalRules() {
    }

    /** Resolved renewal term after defaults have been applied. */
    public record RenewalTerms(
            LocalDate effectiveDate,
            LocalDate expirationDate,
            BigDecimal premiumAmount,
            String premiumCurrency,
            BigDecimal limitAmount,
            String limitCurrency
    ) {
    }

    /**
     * Validates the source lifecycle state and resolves the renewal term.
     *
     * @throws ApiException {@code 409 RENEWAL_NOT_ALLOWED} for an ineligible
     *                      source status, or {@code 400 VALIDATION_ERROR} for an
     *                      invalid date/money field
     */
    public static RenewalTerms validate(Policy source, RenewPolicyRequest request) {
        requireRenewable(source);

        List<ApiException.FieldIssue> issues = new ArrayList<>();
        LocalDate effectiveDate = request.effectiveDate() == null
                ? source.expirationDate().plusDays(1)
                : request.effectiveDate();
        if (request.effectiveDate() != null
                && request.effectiveDate().isBefore(source.expirationDate())) {
            issues.add(new ApiException.FieldIssue("effectiveDate", "INVALID_RENEWAL_EFFECTIVE_DATE",
                    "effectiveDate must be on or after the source expiration date "
                            + source.expirationDate()));
        }

        LocalDate expirationDate = request.expirationDate();
        if (expirationDate == null) {
            issues.add(new ApiException.FieldIssue("expirationDate", "REQUIRED",
                    "expirationDate is required"));
        } else if (!expirationDate.isAfter(effectiveDate)) {
            issues.add(new ApiException.FieldIssue("expirationDate", "INVALID_DATE_RANGE",
                    "expirationDate must be after the renewal effectiveDate " + effectiveDate));
        }

        BigDecimal premiumAmount = request.premiumAmount() == null
                ? source.premiumAmount()
                : request.premiumAmount();
        if (premiumAmount == null) {
            issues.add(new ApiException.FieldIssue("premiumAmount", "REQUIRED",
                    "premiumAmount is required"));
        } else if (premiumAmount.signum() < 0) {
            issues.add(new ApiException.FieldIssue("premiumAmount", "MIN_VALUE",
                    "premiumAmount must be non-negative"));
        }

        BigDecimal limitAmount = request.limitAmount() == null
                ? source.limitAmount()
                : request.limitAmount();
        if (limitAmount == null) {
            issues.add(new ApiException.FieldIssue("limitAmount", "REQUIRED",
                    "limitAmount is required"));
        } else if (limitAmount.signum() < 0) {
            issues.add(new ApiException.FieldIssue("limitAmount", "MIN_VALUE",
                    "limitAmount must be non-negative"));
        }

        String premiumCurrency = request.premiumCurrency() == null
                ? source.premiumCurrency()
                : request.premiumCurrency();
        validateCurrency(issues, "premiumCurrency", premiumCurrency);
        String limitCurrency = request.limitCurrency() == null
                ? source.limitCurrency()
                : request.limitCurrency();
        validateCurrency(issues, "limitCurrency", limitCurrency);

        if (!issues.isEmpty()) {
            throw PolicyErrors.validation("Policy renewal validation failed", issues);
        }
        return new RenewalTerms(effectiveDate, expirationDate, premiumAmount, premiumCurrency,
                limitAmount, limitCurrency);
    }

    /** Enforces the eligible source lifecycle states. */
    public static void requireRenewable(Policy source) {
        String status = source.status();
        if (status != null && RENEWABLE_STATUSES.contains(status)) {
            return;
        }
        String reportedStatus = status == null ? "UNKNOWN" : status;
        throw PolicyErrors.conflict("RENEWAL_NOT_ALLOWED", "status",
                "Policy " + source.policyNumber() + " in status " + reportedStatus
                        + " cannot be renewed.",
                Map.of("sourcePolicyId", source.id().toString(),
                        "sourceStatus", reportedStatus,
                        "allowedStatuses", RENEWABLE_STATUSES.stream().sorted().toList()));
    }

    private static void validateCurrency(List<ApiException.FieldIssue> issues, String field,
                                         String currency) {
        if (currency != null && !currency.matches("[A-Z]{3}")) {
            issues.add(new ApiException.FieldIssue(field, "INVALID_FORMAT",
                    field + " must be an ISO 4217 code"));
        }
    }
}
