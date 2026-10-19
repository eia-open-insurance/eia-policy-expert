package com.eia.policy.expert;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * Application error carrying the HTTP status, stable business code, optional
 * field problems, and optional machine-readable metadata.
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<FieldIssue> details;
    private final Map<String, Object> meta;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of(), Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<FieldIssue> details) {
        this(status, code, message, details, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<FieldIssue> details,
                        Map<String, Object> meta) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = List.copyOf(details);
        this.meta = Map.copyOf(meta);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldIssue> details() {
        return details;
    }

    public Map<String, Object> meta() {
        return meta;
    }

    public record FieldIssue(String field, String code, String message) {
        public FieldIssue(String field, String message) {
            this(field, "INVALID_FIELD", message);
        }
    }
}
