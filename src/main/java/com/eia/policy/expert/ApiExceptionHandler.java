package com.eia.policy.expert;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Emits canonical {@code application/problem+json} responses for the internal
 * Policy expert API (SPEC 6.11, 12.2, 12.6).
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Problem> handleApiException(ApiException ex, HttpServletRequest request) {
        return problem(ex.status(), ex.code(), ex.getMessage(), request, ex.details(), ex.meta());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Problem> handleValidation(MethodArgumentNotValidException ex,
                                                    HttpServletRequest request) {
        List<ApiException.FieldIssue> issues = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiException.FieldIssue(error.getField(),
                        validationCode(error.getCode()),
                        messageOrDefault(error.getDefaultMessage())))
                .forEach(issues::add);
        ex.getBindingResult().getGlobalErrors().stream()
                .map(error -> new ApiException.FieldIssue("_request", "INVALID_REQUEST",
                        messageOrDefault(error.getDefaultMessage())))
                .forEach(issues::add);
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "One or more request fields are invalid.", request, issues, Map.of());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Problem> handleMissingParameter(MissingServletRequestParameterException ex,
                                                           HttpServletRequest request) {
        List<ApiException.FieldIssue> issues = List.of(new ApiException.FieldIssue(
                ex.getParameterName(), "MISSING_PARAMETER",
                "Query parameter '" + ex.getParameterName() + "' is required"));
        return problem(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER",
                "Required query parameter '" + ex.getParameterName() + "' is missing.",
                request, issues, Map.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Problem> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                      HttpServletRequest request) {
        String code = "INVALID_PARAMETER";
        String message = "Parameter '" + ex.getName() + "' is not a valid " + expectedType(ex) + ".";
        List<ApiException.FieldIssue> issues = List.of(new ApiException.FieldIssue(
                ex.getName(), code, message));
        return problem(HttpStatus.BAD_REQUEST, code, message, request, issues, Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Problem> handleUnreadable(HttpMessageNotReadableException ex,
                                                    HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "The request body is missing or malformed.", request, List.of(), Map.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Problem> handleConstraintViolation(ConstraintViolationException ex,
                                                             HttpServletRequest request) {
        List<ApiException.FieldIssue> issues = ex.getConstraintViolations().stream()
                .map(violation -> new ApiException.FieldIssue(
                        lastSegment(violation.getPropertyPath().toString()),
                        "INVALID_FIELD",
                        messageOrDefault(violation.getMessage())))
                .toList();
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "One or more request parameters are invalid.", request, issues, Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Problem> handleNoResource(NoResourceFoundException ex,
                                                    HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "No resource exists at the requested path.", request, List.of(), Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Problem> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                            HttpServletRequest request) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "The HTTP method is not supported for this resource.", request, List.of(), Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Problem> handleMediaType(HttpMediaTypeNotSupportedException ex,
                                                   HttpServletRequest request) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "The Content-Type is not supported; use application/json.", request, List.of(), Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Problem> handleUnexpected(Exception ex, HttpServletRequest request) {
        LOG.error("Unhandled Policy expert error for {}", request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "The Policy expert could not complete the request.", request, List.of(), Map.of());
    }

    private static ResponseEntity<Problem> problem(HttpStatus status, String code, String detail,
                                                   HttpServletRequest request,
                                                   List<ApiException.FieldIssue> issues,
                                                   Map<String, Object> meta) {
        Problem problem = Problems.of(status, code, detail, request.getRequestURI(), issues, meta,
                correlationId(request));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    static String correlationId(HttpServletRequest request) {
        String raw = request.getHeader("X-Correlation-Id");
        if (raw != null && !raw.isBlank()) {
            try {
                return UUID.fromString(raw.trim()).toString();
            } catch (IllegalArgumentException ignored) {
                // A caller-supplied malformed correlation id is replaced, never echoed.
            }
        }
        return UUID.randomUUID().toString();
    }

    private static String validationCode(String springCode) {
        if (springCode == null || springCode.isBlank()) {
            return "INVALID_FIELD";
        }
        return switch (springCode) {
            case "NotNull", "NotBlank", "NotEmpty" -> "REQUIRED";
            case "DecimalMin", "Min", "Positive", "PositiveOrZero" -> "MIN_VALUE";
            case "DecimalMax", "Max", "Negative", "NegativeOrZero" -> "MAX_VALUE";
            case "Digits", "Pattern", "Email" -> "INVALID_FORMAT";
            case "Size", "Length" -> "INVALID_LENGTH";
            default -> "INVALID_FIELD";
        };
    }

    private static String messageOrDefault(String message) {
        return message == null || message.isBlank() ? "is invalid" : message;
    }

    private static String expectedType(MethodArgumentTypeMismatchException ex) {
        Class<?> required = ex.getRequiredType();
        return required == null ? "value" : required.getSimpleName();
    }

    private static String lastSegment(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }
}
