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
    private final UnderwritingSubmissionService service;

    public UnderwritingSubmissionController(UnderwritingSubmissionService service) {
        this.service = service;
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
}
