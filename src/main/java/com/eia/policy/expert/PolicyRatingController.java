package com.eia.policy.expert;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Policy rating API. The external path is {@code /api/v1}; the domain adapter
 * also gets the {@code /internal/v1} alias, matching the other policy command
 * controllers. The tenant comes from {@code ?tenantId=} or {@code
 * X-EIA-Tenant}, and the route is fixed to the DIRECT book.
 *
 * <p>{@code POST} creates one worksheet ({@code 201}); when the request applies
 * the rating, the response also carries the refreshed policy and its new ETag.
 * An optional {@code Idempotency-Key} replays the stored response as {@code
 * 200} with {@code Idempotent-Replayed: true}. {@code GET} returns the newest
 * worksheet for the policy.</p>
 */
@RestController
@RequestMapping({
        "/api/v1/policies/{policyId}/rating",
        "/internal/v1/policies/{policyId}/rating"
})
public class PolicyRatingController {
    private static final String IF_MATCH = "If-Match";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final PolicyRatingService service;
    private final PolicyIdempotencyService idempotencyService;

    public PolicyRatingController(PolicyRatingService service,
                                  PolicyIdempotencyService idempotencyService) {
        this.service = service;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<RatingResult> rate(
            @PathVariable UUID policyId,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody RatingRequest request) {
        RequestScope.requireDirectBook(book);
        IdempotentResponse<RatingResult> outcome = idempotencyService.rate(
                RequestScope.tenant(tenantId, tenantHeader), policyId, request, ifMatch,
                idempotencyKey);
        RatingResult result = outcome.body();
        ResponseEntity.BodyBuilder response = ResponseEntity.status(outcome.status());
        if (result.policy() != null) {
            response.eTag(EntityTags.format(result.policy().version()));
        }
        return response.header(IDEMPOTENT_REPLAYED, Boolean.toString(outcome.replayed()))
                .body(result);
    }

    @GetMapping
    public PolicyRatingWorksheet latest(
            @PathVariable UUID policyId,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book) {
        RequestScope.requireDirectBook(book);
        return service.latest(RequestScope.tenant(tenantId, tenantHeader), policyId);
    }
}
