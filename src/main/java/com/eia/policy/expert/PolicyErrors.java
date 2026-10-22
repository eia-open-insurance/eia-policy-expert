package com.eia.policy.expert;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * Factory methods for the canonical problem exceptions raised by Policy rules.
 * Keeping the status/code pairing here makes the failure taxonomy in SPEC 6.11
 * explicit: 400 validation, 404 not found, 409 state/version conflict,
 * 422 business rule, 428 missing approval.
 */
public final class PolicyErrors {
    private PolicyErrors() {
    }

    public static ApiException badRequest(String code, String field, String message) {
        return badRequest(code, field, message, Map.of());
    }

    public static ApiException badRequest(String code, String field, String message,
                                          Map<String, Object> meta) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message,
                List.of(new ApiException.FieldIssue(field, code, message)), meta);
    }

    public static ApiException validation(String message, List<ApiException.FieldIssue> issues) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, issues);
    }

    public static ApiException notFound(String code, String field, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message,
                List.of(new ApiException.FieldIssue(field, code, message)));
    }

    public static ApiException conflict(String code, String field, String message) {
        return conflict(code, field, message, Map.of());
    }

    public static ApiException conflict(String code, String field, String message,
                                        Map<String, Object> meta) {
        return new ApiException(HttpStatus.CONFLICT, code, message,
                List.of(new ApiException.FieldIssue(field, code, message)), meta);
    }

    public static ApiException businessRule(String code, String field, String message) {
        return businessRule(code, field, message, Map.of());
    }

    public static ApiException businessRule(String code, String field, String message,
                                            Map<String, Object> meta) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message,
                List.of(new ApiException.FieldIssue(field, code, message)), meta);
    }

    public static ApiException approvalRequired(String code, String field, String message) {
        return new ApiException(HttpStatus.PRECONDITION_REQUIRED, code, message,
                List.of(new ApiException.FieldIssue(field, code, message)));
    }
}
