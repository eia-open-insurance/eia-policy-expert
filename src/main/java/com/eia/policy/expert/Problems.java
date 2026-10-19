package com.eia.policy.expert;

import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the canonical {@link Problem} body for the Policy expert. Existing
 * business codes are preserved so the domain client and its callers can keep
 * branching on the same strings.
 */
final class Problems {
    static final String TYPE_BASE = "https://errors.eia-open-insurance.example/policy/";

    private static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("POLICY_NOT_FOUND", "Policy not found"),
            Map.entry("INVALID_STATUS_TRANSITION", "Invalid status transition"),
            Map.entry("INVALID_STATUS", "Invalid policy status"),
            Map.entry("INVALID_INITIAL_STATUS", "Invalid initial policy status"),
            Map.entry("CONCURRENT_MODIFICATION", "Concurrent modification"),
            Map.entry("IDEMPOTENCY_CONFLICT", "Idempotency key conflict"),
            Map.entry("VALIDATION_ERROR", "Request validation failed"),
            Map.entry("MISSING_TENANT_HEADER", "Tenant context required"),
            Map.entry("INVALID_TENANT_HEADER", "Tenant context invalid"),
            Map.entry("MISSING_HEADER", "Request header required"),
            Map.entry("INVALID_IF_MATCH", "Invalid If-Match header"),
            Map.entry("INVALID_IDEMPOTENCY_KEY", "Invalid Idempotency-Key header"),
            Map.entry("DUPLICATE_POLICY_NUMBER", "Duplicate policy number"),
            Map.entry("POLICY_VERSION_NOT_FOUND", "Policy version not found"),
            Map.entry("VERSION_HASH_MISMATCH", "Policy version hash mismatch"),
            Map.entry("VERSION_REGRESSION", "Policy version regression"),
            Map.entry("VERSION_RANGE_OVERLAP", "Policy version overlap"),
            Map.entry("VERSION_ALREADY_ISSUED", "Policy version already issued"),
            Map.entry("VERSION_BACKDATE_REASON_REQUIRED", "Backdate reason required"),
            Map.entry("VERSION_EFFECTIVE_DATE_REQUIRED", "Version effective date required"),
            Map.entry("VERSION_OUTSIDE_POLICY_TERM", "Version outside the policy term"),
            Map.entry("INVALID_VERSION_RANGE", "Invalid policy version range"),
            Map.entry("INVALID_CHANGE_TYPE", "Invalid policy version change type"),
            Map.entry("DUPLICATE_COVERAGE_CODE", "Duplicate coverage code"),
            Map.entry("INVALID_COVERAGE_RANGE", "Invalid coverage date range"),
            Map.entry("COVERAGE_OUTSIDE_POLICY_TERM", "Coverage outside the policy term"),
            Map.entry("INVALID_OCCURRENCE_BASIS", "Invalid occurrence basis"),
            Map.entry("INVALID_COVERAGE_IMPACT", "Invalid coverage impact"),
            Map.entry("INVALID_PER_OCCURRENCE_LIMIT", "Invalid each-occurrence limit"),
            Map.entry("INVALID_AGGREGATE_LIMIT", "Invalid aggregate limit"),
            Map.entry("LIMIT_EXCEEDS_AGGREGATE", "Limit exceeds aggregate"),
            Map.entry("DEDUCTIBLE_EXCEEDS_LIMIT", "Deductible exceeds the each-occurrence limit"),
            Map.entry("INVALID_DEDUCTIBLE", "Invalid deductible"),
            Map.entry("SUBLIMIT_EXCEEDS_LIMIT", "Sublimit exceeds the limit"),
            Map.entry("INVALID_SUBLIMIT", "Invalid sublimit"),
            Map.entry("INVALID_COINSURANCE", "Invalid coinsurance percentage"),
            Map.entry("INVALID_EROSION_BASIS", "Invalid erosion basis"),
            Map.entry("INVALID_AGGREGATE_BASIS", "Invalid aggregate basis"),
            Map.entry("LIMIT_NOT_FOUND", "Policy limit not found"),
            Map.entry("LIMIT_STATE_NOT_FOUND", "Policy limit state not found"),
            Map.entry("LIMIT_EXCEEDED", "Policy limit exceeded"),
            Map.entry("EROSION_STALE_VERSION", "Stale erosion fact"),
            Map.entry("CURRENCY_MISMATCH", "Currency mismatch"),
            Map.entry("ENDORSEMENT_NOT_FOUND", "Endorsement not found"),
            Map.entry("INVALID_ENDORSEMENT_TYPE", "Invalid endorsement type"),
            Map.entry("DUPLICATE_ENDORSEMENT_NUMBER", "Duplicate endorsement number"),
            Map.entry("ENDORSEMENT_OUTSIDE_POLICY_TERM", "Endorsement outside the policy term"),
            Map.entry("BACKDATE_REASON_REQUIRED", "Backdate reason required"),
            Map.entry("INVALID_COVERAGE_CHANGE", "Invalid coverage change"),
            Map.entry("APPROVAL_REQUIRED", "Approval required"),
            Map.entry("POLICY_NOT_ISSUED", "Policy not issued"),
            Map.entry("INVALID_INSURED_ROLE", "Invalid insured role"),
            Map.entry("DUPLICATE_INSURED", "Duplicate insured"),
            Map.entry("NAMED_INSURED_REQUIRED", "Named insured required"),
            Map.entry("INSURED_OUTSIDE_POLICY_TERM", "Insured outside the policy term"),
            Map.entry("DUPLICATE_LOCATION_CODE", "Duplicate location code"),
            Map.entry("LOCATION_OUTSIDE_POLICY_TERM", "Location outside the policy term"),
            Map.entry("INVALID_CANCELLATION_TYPE", "Invalid cancellation type"),
            Map.entry("CANCELLATION_NOT_ALLOWED", "Cancellation not allowed"),
            Map.entry("CANCELLATION_OUTSIDE_POLICY_TERM", "Cancellation outside the policy term"),
            Map.entry("INVALID_CANCELLATION_NOTICE", "Invalid cancellation notice"),
            Map.entry("INSUFFICIENT_CANCELLATION_NOTICE", "Insufficient cancellation notice"),
            Map.entry("POLICY_NOT_CANCELLED", "Policy is not cancelled"),
            Map.entry("REINSTATEMENT_OUTSIDE_POLICY_TERM", "Reinstatement outside the policy term"),
            Map.entry("REINSTATEMENT_BEFORE_CANCELLATION", "Reinstatement before cancellation"),
            Map.entry("PREMIUM_EVIDENCE_REQUIRED", "Premium evidence required"),
            Map.entry("MALFORMED_REQUEST", "Malformed request"),
            Map.entry("MISSING_PARAMETER", "Missing request parameter"),
            Map.entry("INVALID_PARAMETER", "Invalid request parameter"),
            Map.entry("NOT_FOUND", "Not found"),
            Map.entry("METHOD_NOT_ALLOWED", "Method not allowed"),
            Map.entry("UNSUPPORTED_MEDIA_TYPE", "Unsupported media type"),
            Map.entry("INTERNAL_ERROR", "Internal error")
    );

    private Problems() {
    }

    static Problem of(HttpStatus status,
                      String code,
                      String detail,
                      String instance,
                      List<ApiException.FieldIssue> issues,
                      Map<String, Object> meta,
                      String correlationId) {
        String effectiveCode = code == null || code.isBlank() ? "INTERNAL_ERROR" : code;
        List<Problem.FieldError> errors = issues == null || issues.isEmpty()
                ? null
                : issues.stream()
                        .map(issue -> new Problem.FieldError(issue.field(), issue.code(), issue.message()))
                        .toList();
        Map<String, Object> effectiveMeta = meta == null || meta.isEmpty() ? null : meta;
        String effectiveDetail = detail == null || detail.isBlank()
                ? TITLES.getOrDefault(effectiveCode, status.getReasonPhrase())
                : detail;
        return new Problem(
                type(effectiveCode),
                TITLES.getOrDefault(effectiveCode, fallbackTitle(effectiveCode, status)),
                status.value(),
                effectiveDetail,
                instance,
                effectiveCode,
                category(status),
                retryable(status),
                errors,
                effectiveMeta,
                correlationId,
                null
        );
    }

    static URI type(String code) {
        String slug = code == null || code.isBlank()
                ? "internal-error"
                : code.toLowerCase(Locale.ROOT).replace('_', '-');
        return URI.create(TYPE_BASE + slug);
    }

    static String category(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "VALIDATION";
            case UNAUTHORIZED -> "AUTHENTICATION";
            case FORBIDDEN -> "AUTHORIZATION";
            case NOT_FOUND -> "NOT_FOUND";
            case CONFLICT -> "CONFLICT";
            case UNPROCESSABLE_ENTITY -> "BUSINESS_RULE";
            case PRECONDITION_REQUIRED -> "APPROVAL_REQUIRED";
            case TOO_MANY_REQUESTS -> "RATE_LIMIT";
            case BAD_GATEWAY, SERVICE_UNAVAILABLE -> "DEPENDENCY";
            case GATEWAY_TIMEOUT -> "TIMEOUT";
            default -> "INTERNAL";
        };
    }

    static boolean retryable(HttpStatus status) {
        return status == HttpStatus.TOO_MANY_REQUESTS
                || status == HttpStatus.BAD_GATEWAY
                || status == HttpStatus.SERVICE_UNAVAILABLE
                || status == HttpStatus.GATEWAY_TIMEOUT;
    }

    private static String fallbackTitle(String code, HttpStatus status) {
        String reason = status.getReasonPhrase();
        if (reason != null && !reason.isBlank()) {
            return reason;
        }
        String spaced = code == null ? "Request failed" : code.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
