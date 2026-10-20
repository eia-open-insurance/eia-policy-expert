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

import java.util.List;
import java.util.UUID;

/**
 * Dunning actions for a single installment. The public route is also exposed
 * under the service's internal prefix so the Policy domain adapter can reach
 * the same resource. The tenant comes from {@code ?tenantId=} or
 * {@code X-EIA-Tenant}; the route is fixed to the DIRECT book, so another
 * {@code X-EIA-Book} is a 404.
 */
@RestController
@RequestMapping({
        "/api/v1/billing/installments/{installmentId}/dunning",
        "/internal/v1/billing/installments/{installmentId}/dunning"
})
public class BillingDunningController {
    private final BillingDunningService service;

    public BillingDunningController(BillingDunningService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<BillingDunningAction> create(
            @PathVariable UUID installmentId,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book,
            @Valid @RequestBody CreateDunningActionRequest request) {
        RequestScope.requireDirectBook(book);
        BillingDunningAction action = service.create(
                RequestScope.tenant(tenantId, tenantHeader), installmentId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(action);
    }

    @GetMapping
    public List<BillingDunningAction> list(
            @PathVariable UUID installmentId,
            @RequestParam(value = "tenantId", required = false) UUID tenantId,
            @RequestHeader(value = RequestScope.TENANT_HEADER, required = false)
            String tenantHeader,
            @RequestHeader(value = RequestScope.BOOK_HEADER, required = false) String book) {
        RequestScope.requireDirectBook(book);
        return service.list(RequestScope.tenant(tenantId, tenantHeader), installmentId);
    }
}
