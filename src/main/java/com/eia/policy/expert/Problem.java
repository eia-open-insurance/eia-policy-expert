package com.eia.policy.expert;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Canonical RFC 9457 problem body for the Policy expert API. The shape follows
 * {@code schemas/error-envelope.schema.json} (SPEC 6.11 and 12.2).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(
        URI type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        String category,
        boolean retryable,
        List<FieldError> errors,
        Map<String, Object> meta,
        String correlationId,
        String transactionId
) {
    public record FieldError(String field, String code, String message) {
    }
}
