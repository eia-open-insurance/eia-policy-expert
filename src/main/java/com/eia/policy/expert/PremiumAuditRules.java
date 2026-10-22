package com.eia.policy.expert;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * Premium audit invariants.
 *
 * <p>A premium audit is a policy command on a bound or in-force term:
 * {@code BOUND}, {@code ISSUED}, {@code IN_FORCE}, {@code ENDORSED}, or
 * {@code REINSTATED}. The audited premium must be non-negative, the reason is
 * mandatory, and the audited premium must actually change the policy premium so
 * a no-op audit cannot create a meaningless version.</p>
 */
public final class PremiumAuditRules {
    public static final Set<String> AUDITABLE_STATUSES = Set.of(
            "BOUND", "ISSUED", "IN_FORCE", "ENDORSED", "REINSTATED");
    public static final String CHANGE_TYPE = "PREMIUM_AUDIT";
    public static final String ENDORSEMENT_TYPE = "PREMIUM_AUDIT";

    private PremiumAuditRules() {
    }

    /** Enforces the eligible policy lifecycle states. */
    public static void requireAuditable(Policy policy) {
        String status = policy.status();
        if (status != null && AUDITABLE_STATUSES.contains(status)) {
            return;
        }
        String reportedStatus = status == null ? "UNKNOWN" : status;
        throw PolicyErrors.conflict("PREMIUM_AUDIT_NOT_ALLOWED", "status",
                "Policy " + policy.policyNumber() + " in status " + reportedStatus
                        + " cannot be premium-audited.",
                Map.of("policyId", policy.id().toString(),
                        "sourceStatus", reportedStatus,
                        "allowedStatuses", AUDITABLE_STATUSES.stream().sorted().toList()));
    }

    /**
     * Validates the request fields that do not depend on the current version.
     *
     * @throws ApiException {@code 400 PREMIUM_AUDIT_PREMIUM_REQUIRED},
     *                      {@code 400 PREMIUM_AUDIT_PREMIUM_INVALID}, or
     *                      {@code 400 PREMIUM_AUDIT_REASON_REQUIRED}
     */
    public static void validate(PremiumAuditRequest request) {
        if (request == null) {
            throw PolicyErrors.badRequest("PREMIUM_AUDIT_REQUEST_REQUIRED", "request",
                    "A premium audit request is required.");
        }
        if (request.auditedPremium() == null) {
            throw PolicyErrors.badRequest("PREMIUM_AUDIT_PREMIUM_REQUIRED", "auditedPremium",
                    "auditedPremium is required.");
        }
        if (request.auditedPremium().signum() < 0) {
            throw PolicyErrors.badRequest("PREMIUM_AUDIT_PREMIUM_INVALID", "auditedPremium",
                    "auditedPremium must be non-negative.");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw PolicyErrors.badRequest("PREMIUM_AUDIT_REASON_REQUIRED", "reason",
                    "A premium audit reason is required.");
        }
    }

    /**
     * Computes the signed premium movement in policy money scale. Both operands
     * are normalized first so {@code 100} and {@code 100.0000} compare equal.
     */
    public static BigDecimal delta(Policy policy, BigDecimal auditedPremium) {
        return Money.of(auditedPremium).subtract(Money.of(policy.premiumAmount()));
    }

    /** A zero movement is not an audit; it is a rejected no-op. */
    public static void requireChange(BigDecimal delta) {
        if (delta.signum() == 0) {
            throw PolicyErrors.badRequest("NO_PREMIUM_CHANGE", "auditedPremium",
                    "The audited premium must differ from the current policy premium.");
        }
    }

    /** Human-readable audit description, e.g. {@code Premium audit: 120000.0000 -> 150000.0000 (30000.0000)}. */
    public static String description(Policy policy, BigDecimal auditedPremium, BigDecimal delta) {
        return "Premium audit: " + Money.of(policy.premiumAmount()).toPlainString()
                + " -> " + Money.of(auditedPremium).toPlainString()
                + " (" + Money.of(delta).toPlainString() + ")";
    }

    public static String normalizeReason(String reason) {
        return reason == null ? null : reason.trim();
    }
}
