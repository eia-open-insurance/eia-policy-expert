package com.eia.policy.expert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotency-key replay for policy rating and submission conversion.
 *
 * <p>When the caller supplies an {@code Idempotency-Key}, the command first
 * checks the tenant/operation/key scope against the canonical request hash
 * stored on the reservation. An identical request replays as status {@code 200}
 * with {@code replayed=true}; the stored body preserves the original worksheet,
 * policy, and ETag-bearing aggregate version. Reusing the key for a different
 * request is {@code 409 IDEMPOTENCY_CONFLICT} before any write. Otherwise the
 * key is reserved with its request hash, the command runs, and the serialized
 * response is stored on the reservation. The reservation and the command share
 * one transaction, so a failed command leaves no key behind and a successful
 * response is durable before the HTTP response is written.</p>
 */
@Service
public class PolicyIdempotencyService {
    static final String OPERATION_RATING = "rating";
    static final String OPERATION_CONVERSION = "conversion";

    private final PolicyRatingService ratingService;
    private final SubmissionConversionService conversionService;
    private final PolicyIdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    public PolicyIdempotencyService(PolicyRatingService ratingService,
                                    SubmissionConversionService conversionService,
                                    PolicyIdempotencyRepository repository,
                                    ObjectMapper objectMapper) {
        this.ratingService = ratingService;
        this.conversionService = conversionService;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs one rating command idempotently. Without a key the normal
     * {@link PolicyRatingService#rate} path is used unchanged.
     */
    @Transactional
    public IdempotentResponse<RatingResult> rate(UUID tenantId, UUID policyId,
                                                 RatingRequest request, String ifMatch,
                                                 String rawIdempotencyKey) {
        String key = IdempotencyKeys.normalize(rawIdempotencyKey);
        if (key == null) {
            return new IdempotentResponse<>(
                    ratingService.rate(tenantId, policyId, request, ifMatch), 201, false);
        }

        String requestHash = IdempotencyKeys.requestHash(policyId, request);
        Optional<PolicyIdempotencyResponse> existing =
                repository.find(tenantId, OPERATION_RATING, key);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, RatingResult.class);
        }
        if (!repository.reserve(UUID.randomUUID(), tenantId, OPERATION_RATING, key,
                requestHash)) {
            return replay(require(tenantId, OPERATION_RATING, key), requestHash,
                    RatingResult.class);
        }

        RatingResult result = ratingService.rate(tenantId, policyId, request, ifMatch);
        store(tenantId, OPERATION_RATING, key, result, 201);
        return new IdempotentResponse<>(result, 201, false);
    }

    /**
     * Runs one submission conversion idempotently. A key replay returns the
     * policy body from the first call; a state-level replay (already BOUND with
     * a policy) keeps its existing 200 without a key.
     */
    @Transactional
    public IdempotentResponse<Policy> convert(UUID tenantId, UUID submissionId,
                                              ConvertSubmissionRequest request,
                                              String rawIdempotencyKey) {
        String key = IdempotencyKeys.normalize(rawIdempotencyKey);
        if (key == null) {
            return conversion(tenantId, submissionId, request);
        }

        String requestHash = IdempotencyKeys.requestHash(submissionId, request);
        Optional<PolicyIdempotencyResponse> existing =
                repository.find(tenantId, OPERATION_CONVERSION, key);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, Policy.class);
        }
        if (!repository.reserve(UUID.randomUUID(), tenantId, OPERATION_CONVERSION, key,
                requestHash)) {
            return replay(require(tenantId, OPERATION_CONVERSION, key), requestHash,
                    Policy.class);
        }

        IdempotentResponse<Policy> result = conversion(tenantId, submissionId, request);
        store(tenantId, OPERATION_CONVERSION, key, result.body(), result.status());
        return result;
    }

    private IdempotentResponse<Policy> conversion(UUID tenantId, UUID submissionId,
                                                  ConvertSubmissionRequest request) {
        SubmissionConversionResult result = conversionService.convert(
                tenantId, submissionId, request);
        int status = result.replayed() ? 200 : 201;
        return new IdempotentResponse<>(result.policy(), status, result.replayed());
    }

    private PolicyIdempotencyResponse require(UUID tenantId, String operation, String key) {
        return repository.find(tenantId, operation, key)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "INTERNAL_ERROR", "The idempotency reservation could not be resolved."));
    }

    private <T> IdempotentResponse<T> replay(PolicyIdempotencyResponse row, String requestHash,
                                             Class<T> bodyType) {
        // Rows written before response commands stored a request hash carry null
        // and remain replayable; every new reservation has a hash to compare.
        if (row.requestHash() != null && !row.requestHash().equals(requestHash)) {
            throw conflict(row.operation(), row.idempotencyKey());
        }
        if (row.responseJson() == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "The stored idempotency response is incomplete.");
        }
        try {
            T body = objectMapper.readValue(row.responseJson(), bodyType);
            return new IdempotentResponse<>(body, 200, true);
        } catch (JsonProcessingException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "The stored idempotency response could not be read.");
        }
    }

    private static ApiException conflict(String operation, String key) {
        String message = "Idempotency-Key was already used with a different request.";
        return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", message,
                List.of(new ApiException.FieldIssue(
                        "Idempotency-Key", "IDEMPOTENCY_CONFLICT", message)),
                Map.of("operation", operation, "key", key));
    }

    private void store(UUID tenantId, String operation, String key, Object body, int status) {
        try {
            repository.complete(tenantId, operation, key,
                    objectMapper.writeValueAsString(body), status);
        } catch (JsonProcessingException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "The idempotency response could not be serialized.");
        }
    }
}
