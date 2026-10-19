package com.eia.policy.expert;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Application error that carries the HTTP status, stable error code, and optional field details.
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<FieldIssue> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<FieldIssue> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = List.copyOf(details);
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

    public record FieldIssue(String field, String message) {}
}
