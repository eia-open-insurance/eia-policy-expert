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
 * Policy credit note API.
 *
 * <p>The public paths are also exposed under the service's internal prefix so
 * the Policy domain adapter can reach the same resource. The tenant comes from
 * {@code ?tenantId=} or {@code X-EIA-Tenant}; the route is fixed to the DIRECT
 * book, so another {@code X-EIA-Book} is a 404.</p>
 */
@RestController
@RequestMapping({
        "/api/v1/policies/{policyId}/credit-notes",
        "/internal/v1/policies/{policyId}/credit-notes"
})
public class CreditNoteController {
    private final CreditNoteService service;

    public CreditNoteController(CreditNoteService service) {
        this.service = service;
    }

    @GetMapping
    public List<PolicyCreditNote> list(
            @PathVariable UUID policyId,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book) {
        RequestScope.requireDirectBook(book);
        return service.list(RequestScope.tenant(tenantId, tenantHeader), policyId);
    }

    @PostMapping
    public ResponseEntity<PolicyCreditNote> create(
            @PathVariable UUID policyId,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book,
            @Valid @RequestBody CreateCreditNoteRequest request) {
        RequestScope.requireDirectBook(book);
        PolicyCreditNote created = service.create(
                RequestScope.tenant(tenantId, tenantHeader), policyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{creditNoteId}/status")
    public PolicyCreditNote updateStatus(
            @PathVariable UUID policyId,
            @PathVariable UUID creditNoteId,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book,
            @Valid @RequestBody UpdateCreditNoteStatusRequest request) {
        RequestScope.requireDirectBook(book);
        return service.updateStatus(RequestScope.tenant(tenantId, tenantHeader), policyId,
                creditNoteId, request);
    }
}
