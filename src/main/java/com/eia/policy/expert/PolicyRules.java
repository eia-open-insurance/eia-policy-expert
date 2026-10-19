package com.eia.policy.expert;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Policy invariants shared by create and status-change operations.
 */
public final class PolicyRules {
    public static final Set<String> STATUSES = Set.of(
            "DRAFT", "QUOTED", "BOUND", "ISSUED", "IN_FORCE", "ENDORSED",
            "RENEWED", "CANCELLED", "EXPIRED", "NON_RENEWED", "REINSTATED", "VOID"
    );

    private static final Map<String, Set<String>> TRANSITIONS = Map.ofEntries(
            Map.entry("DRAFT", Set.of("QUOTED", "CANCELLED")),
            Map.entry("QUOTED", Set.of("BOUND", "EXPIRED")),
            Map.entry("BOUND", Set.of("ISSUED")),
            Map.entry("ISSUED", Set.of("IN_FORCE")),
            Map.entry("IN_FORCE", Set.of("ENDORSED", "CANCELLED", "EXPIRED", "NON_RENEWED", "RENEWED", "VOID")),
            Map.entry("ENDORSED", Set.of("IN_FORCE")),
            Map.entry("CANCELLED", Set.of("REINSTATED", "VOID")),
            Map.entry("REINSTATED", Set.of("IN_FORCE")),
            Map.entry("EXPIRED", Set.of("RENEWED", "REINSTATED")),
            Map.entry("RENEWED", Set.of()),
            Map.entry("NON_RENEWED", Set.of()),
            Map.entry("VOID", Set.of())
    );

    private PolicyRules() {
    }

    public static void validateCreate(
            String initialStatus,
            LocalDate effectiveDate,
            LocalDate expirationDate,
            BigDecimal premiumAmount,
            BigDecimal limitAmount
    ) {
        List<ApiException.FieldIssue> issues = new ArrayList<>();
        if (effectiveDate == null) {
            issues.add(new ApiException.FieldIssue("effectiveDate", "effectiveDate is required"));
        }
        if (expirationDate == null) {
            issues.add(new ApiException.FieldIssue("expirationDate", "expirationDate is required"));
        }
        if (effectiveDate != null && expirationDate != null && effectiveDate.isAfter(expirationDate)) {
            issues.add(new ApiException.FieldIssue("expirationDate",
                    "expirationDate must be on or after effectiveDate"));
        }
        if (premiumAmount == null) {
            issues.add(new ApiException.FieldIssue("premiumAmount", "premiumAmount is required"));
        } else if (premiumAmount.signum() < 0) {
            issues.add(new ApiException.FieldIssue("premiumAmount", "premiumAmount must be non-negative"));
        }
        if (limitAmount == null) {
            issues.add(new ApiException.FieldIssue("limitAmount", "limitAmount is required"));
        } else if (limitAmount.signum() < 0) {
            issues.add(new ApiException.FieldIssue("limitAmount", "limitAmount must be non-negative"));
        }
        if (!issues.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Policy validation failed", issues);
        }
        normalizeInitialStatus(initialStatus);
    }

    public static String normalizeInitialStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return "DRAFT";
        }
        String status = normalizeStatus(rawStatus);
        if (!"DRAFT".equals(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INITIAL_STATUS",
                    "A policy must be created as DRAFT and advanced through the lifecycle");
        }
        return status;
    }

    public static String normalizeStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "status is required");
        }
        String status = rawStatus.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS",
                    "Unknown policy status: " + rawStatus);
        }
        return status;
    }

    public static String validateTransition(String fromStatus, String toStatus) {
        String from = normalizeStatus(fromStatus);
        String to = normalizeStatus(toStatus);
        if (from.equals(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS_TRANSITION",
                    "Policy is already in status " + from);
        }
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS_TRANSITION",
                    "Cannot transition policy from " + from + " to " + to);
        }
        return to;
    }
}
