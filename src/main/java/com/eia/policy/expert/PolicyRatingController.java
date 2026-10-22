package com.eia.policy.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
 * {@code GET} returns the newest worksheet for the policy.</p>
 */
@RestController
@RequestMapping({
        "/api/v1/policies/{policyId}/rating",
        "/internal/v1/policies/{policyId}/rating"
})
public class PolicyRatingController {
    private static final String IF_MATCH = "If-Match";

    private final PolicyRatingService service;

    public PolicyRatingController(PolicyRatingService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RatingResult> rate(
            @PathVariable UUID policyId,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody RatingRequest request) {
        RequestScope.requireDirectBook(book);
        RatingResult result = service.rate(RequestScope.tenant(tenantId, tenantHeader),
                policyId, request, ifMatch);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.policy() != null) {
            response.eTag(EntityTags.format(result.policy().version()));
        }
        return response.body(result);
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
