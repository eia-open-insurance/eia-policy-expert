package com.eia.policy.expert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Underwriting submission API.
 *
 * <p>Submissions are tenant-owned aggregates that exist before a policy, so
 * they are mounted under the policy API namespace rather than under one policy
 * id. The tenant comes from {@code ?tenantId=} or {@code X-EIA-Tenant}; the
 * route is fixed to the DIRECT book, so another {@code X-EIA-Book} is a
 * 404.</p>
 */
@RestController
@RequestMapping({
        "/api/v1/policies/submissions",
        "/internal/v1/policies/submissions"
})
public class UnderwritingSubmissionController {
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final UnderwritingSubmissionService service;
    private final PolicyIdempotencyService idempotencyService;

    public UnderwritingSubmissionController(UnderwritingSubmissionService service,
                                            PolicyIdempotencyService idempotencyService) {
        this.service = service;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping
    public List<UnderwritingSubmissionView> list(
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "status", required = false) String status) {
        RequestScope.requireDirectBook(book);
        return service.list(RequestScope.tenant(tenantId, tenantHeader), q, status);
    }

    @GetMapping("/{id}")
    public UnderwritingSubmissionView get(
            @PathVariable UUID id,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book) {
        RequestScope.requireDirectBook(book);
        return service.get(RequestScope.tenant(tenantId, tenantHeader), id);
    }

    @PostMapping
    public ResponseEntity<UnderwritingSubmissionView> create(
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book,
            @Valid @RequestBody CreateUnderwritingSubmissionRequest request) {
        RequestScope.requireDirectBook(book);
        UnderwritingSubmissionView created = service.create(
                RequestScope.tenant(tenantId, tenantHeader), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(Integer.toString(created.version()))
                .body(created);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<UnderwritingSubmissionView> updateStatus(
            @PathVariable UUID id,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book,
            @Valid @RequestBody UpdateUnderwritingSubmissionStatusRequest request) {
        RequestScope.requireDirectBook(book);
        UnderwritingSubmissionView updated = service.updateStatus(
                RequestScope.tenant(tenantId, tenantHeader), id, request);
        return ResponseEntity.ok()
                .eTag(Integer.toString(updated.version()))
                .body(updated);
    }

    /**
     * Converts a QUOTED (or BOUND) submission into its DRAFT policy. A new
     * conversion is {@code 201}; replaying an already-converted submission or a
     * stored {@code Idempotency-Key} is {@code 200} with {@code
     * Idempotent-Replayed: true} and the same policy body/ETag. The body
     * supplies the premium and optional expiration; all other terms come from
     * the submission.
     */
    @PostMapping("/{id}/convert")
    public ResponseEntity<Policy> convert(
            @PathVariable UUID id,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody ConvertSubmissionRequest request) {
        RequestScope.requireDirectBook(book);
        IdempotentResponse<Policy> outcome = idempotencyService.convert(
                RequestScope.tenant(tenantId, tenantHeader), id, request, idempotencyKey);
        return ResponseEntity.status(outcome.status())
                .eTag(Integer.toString(outcome.body().version()))
                .header(IDEMPOTENT_REPLAYED, Boolean.toString(outcome.replayed()))
                .body(outcome.body());
    }
}
